/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.*;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.ElasticSearchIllegalStateException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.docset.AllDocIdSet;
import org.elasticsearch.common.lucene.docset.ContextDocIdSet;
import org.elasticsearch.common.lucene.search.*;
import org.elasticsearch.search.SearchParseElement;
import org.elasticsearch.search.SearchPhase;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.query.QueryPhaseExecutionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class FacetPhase implements SearchPhase {

    private final FacetParseElement facetParseElement;

    private final FacetBinaryParseElement facetBinaryParseElement;

    @Inject
    public FacetPhase(FacetParseElement facetParseElement, FacetBinaryParseElement facetBinaryParseElement) {
        this.facetParseElement = facetParseElement;
        this.facetBinaryParseElement = facetBinaryParseElement;
    }

    @Override
    public Map<String, ? extends SearchParseElement> parseElements() {
        return ImmutableMap.of("facets", facetParseElement, "facets_binary", facetBinaryParseElement, "facetsBinary", facetBinaryParseElement);
    }

    @Override
    public void preProcess(SearchContext context) {
        if (context.facets() != null && context.facets().hasQuery()) {
            for (SearchContextFacets.Entry entry : context.facets().entries()) {
                if (entry.isGlobal()) {
                    continue;
                }
                if (entry.getMode() == FacetExecutor.Mode.COLLECTOR) {
                    Collector collector = entry.getFacetExecutor().collector();
                    if (entry.getFilter() != null) {
                        collector = new FilteredCollector(collector, entry.getFilter());
                    }
                    context.searcher().addMainQueryCollector(collector);
                } else if (entry.getMode() == FacetExecutor.Mode.POST) {
                    context.searcher().enableMainDocIdSetCollector();
                } else {
                    throw new ElasticSearchIllegalStateException("what mode?");
                }
            }
        }
    }

    @Override
    public void execute(SearchContext context) throws ElasticSearchException {
        if (context.facets() == null) {
            return;
        }

        if (context.queryResult().facets() != null) {
            // no need to compute the facets twice, they should be computed on a per context basis
            return;
        }

        Map<Filter, List<Collector>> filtersByCollector = null;
        List<ContextDocIdSet> globalDocSets = null;
        for (SearchContextFacets.Entry entry : context.facets().entries()) {
            if (!entry.isGlobal()) {
                if (entry.getMode() == FacetExecutor.Mode.POST) {
                    FacetExecutor.Post post = entry.getFacetExecutor().post();
                    if (entry.getFilter() != null) {
                        post = new FacetExecutor.Post.Filtered(post, entry.getFilter());
                    }
                    try {
                        post.executePost(context.searcher().mainDocIdSetCollector().docSets());
                    } catch (Exception e) {
                        throw new QueryPhaseExecutionException(context, "failed to execute facet [" + entry.getFacetName() + "]", e);
                    }
                }
            } else {
                if (entry.getMode() == FacetExecutor.Mode.POST) {
                    if (globalDocSets == null) {
                        // build global post entries, map a reader context to a live docs docIdSet
                        List<AtomicReaderContext> leaves = context.searcher().getIndexReader().leaves();
                        globalDocSets = new ArrayList<ContextDocIdSet>(leaves.size());
                        for (AtomicReaderContext leaf : leaves) {
                            globalDocSets.add(new ContextDocIdSet(
                                    leaf,
                                    BitsFilteredDocIdSet.wrap(new AllDocIdSet(leaf.reader().maxDoc()), leaf.reader().getLiveDocs())) // need to only include live docs
                            );
                        }
                    }
                    try {
                        FacetExecutor.Post post = entry.getFacetExecutor().post();
                        if (entry.getFilter() != null) {
                            post = new FacetExecutor.Post.Filtered(post, entry.getFilter());
                        }
                        post.executePost(globalDocSets);
                    } catch (Exception e) {
                        throw new QueryPhaseExecutionException(context, "Failed to execute facet [" + entry.getFacetName() + "]", e);
                    }
                } else if (entry.getMode() == FacetExecutor.Mode.COLLECTOR) {
                    Filter filter = Queries.MATCH_ALL_FILTER;
                    if (entry.getFilter() != null) {
                        filter = entry.getFilter();
                    }
                    if (filtersByCollector == null) {
                        filtersByCollector = Maps.newHashMap();
                    }
                    List<Collector> list = filtersByCollector.get(filter);
                    if (list == null) {
                        list = new ArrayList<Collector>();
                        filtersByCollector.put(filter, list);
                    }
                    list.add(entry.getFacetExecutor().collector());
                }
            }
        }

        // optimize the global collector based execution
        if (filtersByCollector != null) {
            // now, go and execute the filters->collector ones
            for (Map.Entry<Filter, List<Collector>> entry : filtersByCollector.entrySet()) {
                Filter filter = entry.getKey();
                Query query = new XConstantScoreQuery(filter);
                Filter searchFilter = context.mapperService().searchFilter(context.types());
                if (searchFilter != null) {
                    query = new XFilteredQuery(query, context.filterCache().cache(searchFilter));
                }
                try {
                    context.searcher().search(query, MultiCollector.wrap(entry.getValue().toArray(new Collector[entry.getValue().size()])));
                } catch (Exception e) {
                    throw new QueryPhaseExecutionException(context, "Failed to execute global facets", e);
                }
                for (Collector collector : entry.getValue()) {
                    if (collector instanceof XCollector) {
                        ((XCollector) collector).postCollection();
                    }
                }
            }
        }

        List<Facet> facets = new ArrayList<Facet>(context.facets().entries().size());
        for (SearchContextFacets.Entry entry : context.facets().entries()) {
            facets.add(entry.getFacetExecutor().buildFacet(entry.getFacetName()));
        }
        context.queryResult().facets(new InternalFacets(facets));
    }
}
