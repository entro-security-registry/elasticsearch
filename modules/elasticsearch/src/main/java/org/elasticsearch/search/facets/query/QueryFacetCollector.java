/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.search.facets.query;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.*;
import org.elasticsearch.index.cache.filter.FilterCache;
import org.elasticsearch.search.facets.Facet;
import org.elasticsearch.search.facets.collector.FacetCollector;
import org.elasticsearch.util.lucene.docset.DocSet;
import org.elasticsearch.util.lucene.docset.DocSets;

import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
public class QueryFacetCollector extends FacetCollector {

    private final Filter filter;

    private final String name;

    private DocSet docSet;

    private int count = 0;

    public QueryFacetCollector(String name, Query query, FilterCache filterCache) {
        this.name = name;
        this.filter = filterCache.cache(new QueryWrapperFilter(query));
    }

    @Override public void setScorer(Scorer scorer) throws IOException {
        // ignore
    }

    @Override public void setNextReader(IndexReader reader, int docBase) throws IOException {
        DocIdSet docIdSet = filter.getDocIdSet(reader);
        if (docIdSet instanceof DocSet) {
            docSet = (DocSet) docIdSet;
        } else {
            docSet = DocSets.cacheable(reader, docIdSet);
        }
    }

    @Override public void collect(int doc) throws IOException {
        if (docSet.get(doc)) {
            count++;
        }
    }

    @Override public boolean acceptsDocsOutOfOrder() {
        return true;
    }

    @Override public Facet facet() {
        return new InternalQueryFacet(name, count);
    }
}
