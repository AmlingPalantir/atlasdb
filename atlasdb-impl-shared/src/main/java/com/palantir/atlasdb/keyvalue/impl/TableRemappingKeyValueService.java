/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.impl;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.ForwardingObject;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.keyvalue.NamespacedKeyValueService;
import com.palantir.atlasdb.keyvalue.TableMappingService;
import com.palantir.atlasdb.keyvalue.api.BatchColumnRangeSelection;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.ColumnRangeSelection;
import com.palantir.atlasdb.keyvalue.api.ColumnSelection;
import com.palantir.atlasdb.keyvalue.api.KeyAlreadyExistsException;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.RangeRequest;
import com.palantir.atlasdb.keyvalue.api.RowColumnRangeIterator;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.common.base.ClosableIterator;
import com.palantir.util.paging.TokenBackedBasicResultsPage;

public final class TableRemappingKeyValueService extends ForwardingObject implements
        NamespacedKeyValueService {
    public static TableRemappingKeyValueService create(KeyValueService delegate,
                                                       TableMappingService tableMapper) {
        return new TableRemappingKeyValueService(delegate, tableMapper);
    }

    private final KeyValueService delegate;

    private final TableMappingService tableMapper;

    private TableRemappingKeyValueService(KeyValueService delegate, TableMappingService tableMapper) {
        this.delegate = delegate;
        this.tableMapper = tableMapper;
    }

    @Override
    public TableMappingService getTableMapper() {
        return tableMapper;
    }

    @Override
    public void addGarbageCollectionSentinelValues(TableReference tableRef, Set<Cell> cells) {
        try {
            delegate().addGarbageCollectionSentinelValues(
                    tableMapper.getMappedTableName(tableRef),
                    cells);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void createTable(TableReference tableRef, byte[] tableMetadata) {
        TableReference shortName = tableMapper.addTable(tableRef);
        delegate().createTable(shortName, tableMetadata);
    }

    @Override
    public void createTables(Map<TableReference, byte[]> tableReferencesToTableMetadata) {
        Map<TableReference, byte[]> tableNameToTableMetadata =
                Maps.newHashMapWithExpectedSize(tableReferencesToTableMetadata.size());
        for (Entry<TableReference, byte[]> tableEntry : tableReferencesToTableMetadata.entrySet()) {
            tableNameToTableMetadata.put(
                    tableMapper.addTable(tableEntry.getKey()),
                    tableEntry.getValue());
        }
        delegate().createTables(tableNameToTableMetadata);
    }

    @Override
    protected KeyValueService delegate() {
        return delegate;
    }

    @Override
    public void delete(TableReference tableRef, Multimap<Cell, Long> keys) {
        try {
            delegate().delete(tableMapper.getMappedTableName(tableRef), keys);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void dropTable(TableReference tableRef) {
        dropTables(ImmutableSet.of(tableRef));
    }

    @Override
    public void dropTables(Set<TableReference> tableRefs) {
        Set<TableReference> tableNames = getShortTableReferencesForExistingTables(tableRefs);
        delegate().dropTables(tableNames);

        // We're purposely updating the table mappings after all drops are complete
        for (TableReference tableRef : tableRefs) {
            // Handles the edge case of deleting _namespace when clearing the kvs
            if (tableRef.equals(AtlasDbConstants.NAMESPACE_TABLE)) {
                break;
            }
            tableMapper.removeTable(tableRef);
        }
    }

    private Set<TableReference> getShortTableReferencesForExistingTables(Set<TableReference> tableRefs) {
        Set<TableReference> tableNames = Sets.newHashSetWithExpectedSize(tableRefs.size());
        for (TableReference tableRef : tableRefs) {
            try {
                tableNames.add(tableMapper.getMappedTableName(tableRef));
            } catch (TableMappingNotFoundException e) {
                // Table does not exist - do nothing
            }
        }
        return tableNames;
    }

    @Override
    public Map<Cell, Value> get(TableReference tableRef, Map<Cell, Long> timestampByCell) {
        try {
            return delegate().get(tableMapper.getMappedTableName(tableRef), timestampByCell);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Set<TableReference> getAllTableNames() {
        return tableMapper.mapToFullTableNames(delegate().getAllTableNames());
    }

    @Override
    public Multimap<Cell, Long> getAllTimestamps(TableReference tableRef,
                                                 Set<Cell> keys,
                                                 long timestamp) {
        try {
            return delegate().getAllTimestamps(tableMapper.getMappedTableName(tableRef), keys, timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Collection<? extends KeyValueService> getDelegates() {
        return ImmutableSet.of(delegate);
    }

    @Override
    public Map<RangeRequest, TokenBackedBasicResultsPage<RowResult<Value>, byte[]>> getFirstBatchForRanges(
            TableReference tableRef,
            Iterable<RangeRequest> rangeRequests,
            long timestamp) {
        try {
            return delegate().getFirstBatchForRanges(
                    tableMapper.getMappedTableName(tableRef),
                    rangeRequests,
                    timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Map<Cell, Long> getLatestTimestamps(TableReference tableRef,
                                               Map<Cell, Long> timestampByCell) {
        try {
            return delegate().getLatestTimestamps(
                    tableMapper.getMappedTableName(tableRef),
                    timestampByCell);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public byte[] getMetadataForTable(TableReference tableRef) {
        try {
            return delegate().getMetadataForTable(tableMapper.getMappedTableName(tableRef));
        } catch (TableMappingNotFoundException e) { // table does not exist.
            return AtlasDbConstants.EMPTY_TABLE_METADATA;
        }
    }

    @Override
    public Map<TableReference, byte[]> getMetadataForTables() {
        Map<TableReference, byte[]> tableRefToBytes = Maps.newHashMap();
        for (Entry<TableReference, byte[]> entry : delegate().getMetadataForTables().entrySet()) {
            tableRefToBytes.put(
                    Iterables.getOnlyElement(tableMapper.mapToFullTableNames(ImmutableSet.of(entry.getKey()))),
                    entry.getValue());
        }
        return tableRefToBytes;
    }

    @Override
    public ClosableIterator<RowResult<Value>> getRange(TableReference tableRef,
                                                       RangeRequest rangeRequest,
                                                       long timestamp) {
        try {
            return delegate().getRange(tableMapper.getMappedTableName(tableRef), rangeRequest, timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public ClosableIterator<RowResult<Set<Long>>> getRangeOfTimestamps(TableReference tableRef,
                                                                       RangeRequest rangeRequest,
                                                                       long timestamp) {
        try {
            return delegate().getRangeOfTimestamps(
                    tableMapper.getMappedTableName(tableRef),
                    rangeRequest,
                    timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Map<Cell, Value> getRows(TableReference tableRef,
                                    Iterable<byte[]> rows,
                                    ColumnSelection columnSelection,
                                    long timestamp) {
        try {
            return delegate().getRows(
                    tableMapper.getMappedTableName(tableRef),
                    rows,
                    columnSelection,
                    timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Map<byte[], RowColumnRangeIterator> getRowsColumnRange(
            TableReference tableRef,
            Iterable<byte[]> rows,
            BatchColumnRangeSelection columnRangeSelection,
            long timestamp) {
        try {
            return delegate().getRowsColumnRange(tableMapper.getMappedTableName(tableRef),
                    rows,
                    columnRangeSelection,
                    timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public RowColumnRangeIterator getRowsColumnRange(TableReference tableRef,
                                                     Iterable<byte[]> rows,
                                                     ColumnRangeSelection columnRangeSelection,
                                                     int cellBatchHint,
                                                     long timestamp) {
        try {
            return delegate().getRowsColumnRange(tableMapper.getMappedTableName(tableRef),
                                                 rows,
                                                 columnRangeSelection,
                                                 cellBatchHint,
                                                 timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void multiPut(Map<TableReference, ? extends Map<Cell, byte[]>> valuesByTable,
                         long timestamp) {
        try {
            delegate().multiPut(tableMapper.mapToShortTableNames(valuesByTable), timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void put(TableReference tableRef, Map<Cell, byte[]> values, long timestamp) {
        try {
            delegate().put(tableMapper.getMappedTableName(tableRef), values, timestamp);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void putMetadataForTable(TableReference tableRef, byte[] metadata) {
        try {
            delegate().putMetadataForTable(tableMapper.getMappedTableName(tableRef), metadata);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void putMetadataForTables(Map<TableReference, byte[]> tableReferencesToMetadata) {
        Map<TableReference, byte[]> tableNameToMetadata =
                Maps.newHashMapWithExpectedSize(tableReferencesToMetadata.size());
        for (Entry<TableReference, byte[]> tableEntry : tableReferencesToMetadata.entrySet()) {
            try {
                tableNameToMetadata.put(
                        tableMapper.getMappedTableName(tableEntry.getKey()),
                        tableEntry.getValue());
            } catch (TableMappingNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
        }
        delegate().putMetadataForTables(tableNameToMetadata);
    }

    @Override
    public void putUnlessExists(TableReference tableRef, Map<Cell, byte[]> values)
            throws KeyAlreadyExistsException {
        try {
            delegate().putUnlessExists(tableMapper.getMappedTableName(tableRef), values);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void putWithTimestamps(TableReference tableRef, Multimap<Cell, Value> values) {
        try {
            delegate().putWithTimestamps(tableMapper.getMappedTableName(tableRef), values);
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void close() {
        delegate().close();
    }

    @Override
    public void truncateTable(TableReference tableRef) {
        try {
            delegate().truncateTable(tableMapper.getMappedTableName(tableRef));
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void truncateTables(Set<TableReference> tableRefs) {
        Set<TableReference> tablesToTruncate = Sets.newHashSet();
        for (TableReference tableRef : tableRefs) {
            try {
                tablesToTruncate.add(tableMapper.getMappedTableName(tableRef));
            } catch (TableMappingNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
        }
        delegate().truncateTables(tablesToTruncate);
    }

    @Override
    public void compactInternally(TableReference tableRef) {
        try {
            delegate().compactInternally(tableMapper.getMappedTableName(tableRef));
        } catch (TableMappingNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
