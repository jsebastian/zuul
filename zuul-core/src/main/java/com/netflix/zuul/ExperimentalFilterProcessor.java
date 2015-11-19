/*
 *
 *  Copyright 2013-2015 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.zuul;

import com.netflix.zuul.context.Debug;
import com.netflix.zuul.filters.FilterSyncType;
import com.netflix.zuul.filters.SyncZuulFilter;
import com.netflix.zuul.filters.ZuulFilter;
import com.netflix.zuul.message.ZuulMessage;
import rx.Observable;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

import static com.netflix.zuul.ExecutionStatus.*;


/**
 * Experimenting with how to optimise use of Observable/Single when not required (ie. for Sync filters).
 *
 * Also need to find a way to break out of filter chain processing (ie. when choose to throttle or reject requests) more
 * efficiently, as current mechanism is not shedding much load.
 *
 * @author Mike Smith
 */
@Singleton
public class ExperimentalFilterProcessor extends FilterProcessorImpl
{
    @Inject
    public ExperimentalFilterProcessor(FilterLoader loader, FilterUsageNotifier usageNotifier) {
        super(loader, usageNotifier);
    }

    /**
     * Apply each of the filters, grouping any consecutive sync filters into batches wrapped in single Observables.
     *
     * @param chain
     * @param filters
     * @return
     */
    protected Observable<ZuulMessage> applyFilters(Observable<ZuulMessage> chain, List<ZuulFilter> filters)
    {
        ArrayList<SyncZuulFilter> batchOfSyncFilters = new ArrayList<>();
        for (ZuulFilter filter : filters)
        {
            if (filter.getSyncType() == FilterSyncType.SYNC) {
                batchOfSyncFilters.add((SyncZuulFilter) filter);
            }
            else {
                // if we have a batch of consecutive sync filters, then combine them now into
                // one Observable.
                if (batchOfSyncFilters.size() > 0) {
                    chain = processSyncFilters(chain, batchOfSyncFilters);
                    batchOfSyncFilters = new ArrayList<>();
                }

                // And then add this async filter to chain too.
                chain = processFilterAsObservable(chain, filter, false);
            }
        }

        // Add any remaining sync filters.
        if (batchOfSyncFilters.size() > 0) {
            chain = processSyncFilters(chain, batchOfSyncFilters);
            batchOfSyncFilters = new ArrayList<>();
        }

        return chain;
    }

    /**
     * Process multiple consecutive sync filters, wrapping only the final result in an Observable.
     *
     * @param input
     * @param filters
     * @return
     */
    protected Observable<ZuulMessage> processSyncFilters(Observable<ZuulMessage> input, final List<SyncZuulFilter> filters)
    {
        return input.map(msg -> {
                    for (SyncZuulFilter filter : filters) {
                        msg = processSyncFilter(msg, filter);
                    }
                    return msg;
                }
        );
    }

    /**
     * TODO - find a way to remove some of the duplication between this method and processAsyncFilter().
     *
     * @param msg
     * @param filter
     * @return
     */
    public ZuulMessage processSyncFilter(ZuulMessage msg, SyncZuulFilter filter)
    {
        final FilterExecInfo info = new FilterExecInfo();
        info.bDebug = msg.getContext().debugRouting();

        if (info.bDebug) {
            Debug.addRoutingDebug(msg.getContext(), "Filter " + filter.filterType().toString() + " " + filter.filterOrder() + " " + filter.filterName());
            info.debugCopy = msg.clone();
        }

        // Apply this filter.
        ZuulMessage result;
        long ltime = System.currentTimeMillis();
        try {
            if (filter.isDisabled()) {
                result = filter.getDefaultOutput(msg);
                info.status = DISABLED;
            }
            else if (msg.getContext().shouldStopFilterProcessing() && ! filter.overrideStopFilterProcessing()) {
                // This is typically set by a filter when wanting to reject a request, and also reduce load on the server by
                // not processing any more filters.
                result = filter.getDefaultOutput(msg);
                info.status = SKIPPED;
            }
            else {
                // Only apply the filter if the shouldFilter() method is true.
                if (filter.shouldFilter(msg)) {
                    result = filter.apply(msg);

                    // If no result returned from filter, then use the original input.
                    if (result == null) {
                        result = filter.getDefaultOutput(msg);
                    }
                }
                else {
                    result = filter.getDefaultOutput(msg);
                    info.status = SKIPPED;
                }
            }
        }
        catch (Exception e) {
            result = filter.getDefaultOutput(msg);
            msg.getContext().setError(e);
            info.status = FAILED;
            recordFilterError(filter, msg, e);
        }

        // Record info when filter processing completes.
        if (info.status == null) {
            info.status = SUCCESS;
        }
        info.execTime = System.currentTimeMillis() - ltime;
        recordFilterCompletion(result, filter, info);

        return result;
    }
}
