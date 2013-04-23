package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.execution.Sitevars;
import com.facebook.presto.metadata.AliasDao;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.metadata.ImportTableHandle;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.NativeTableHandle;
import com.facebook.presto.metadata.Node;
import com.facebook.presto.metadata.NodeManager;
import com.facebook.presto.metadata.QualifiedTableName;
import com.facebook.presto.metadata.ShardManager;
import com.facebook.presto.metadata.TableAlias;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.sql.analyzer.Session;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.analyzer.Type;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeRewriter;
import com.facebook.presto.sql.planner.plan.PlanRewriter;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class TableAliasSelector
    extends PlanOptimizer
{
    private final Metadata metadata;
    private final AliasDao aliasDao;
    private final NodeManager nodeManager;
    private final ShardManager shardManager;
    private final Sitevars sitevars;

    public TableAliasSelector(Metadata metadata,
            AliasDao aliasDao,
            NodeManager nodeManager,
            ShardManager shardManager,
            Sitevars sitevars)
    {
        this.metadata = checkNotNull(metadata, "metadata is null");
        this.aliasDao = checkNotNull(aliasDao, "aliasDao is null");
        this.nodeManager = checkNotNull(nodeManager, "nodeManager is null");
        this.shardManager = checkNotNull(shardManager, "shardManager is null");
        this.sitevars = checkNotNull(sitevars, "sitevars is null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, Map<Symbol, Type> types)
    {
        checkNotNull(plan, "plan is null");
        checkNotNull(session, "session is null");
        checkNotNull(types, "types is null");

        // don't optimize plans that actually write local tables. We always want to
        // read the remote table in that case.
        if (containsTableWriter(plan)) {
            return plan;
        }

        if (sitevars.isAliasEnabled()) {
            return PlanRewriter.rewriteWith(new Rewriter(), plan);
        }
        else {
            return plan;
        }
    }

    private boolean containsTableWriter(PlanNode plan)
    {
        if (plan == null) {
            return false;
        }
        else if (plan instanceof TableWriterNode) {
            return true;
        }
        else {
            for (PlanNode sourceNode : plan.getSources()) {
                if (containsTableWriter(sourceNode)) {
                    return true;
                }
            }
        }

        return false;
    }

    private class Rewriter
        extends PlanNodeRewriter<Void>
    {
        @Override
        public PlanNode rewriteTableScan(TableScanNode node, Void context, PlanRewriter<Void> planRewriter)
        {
            TableHandle tableHandle = node.getTable();

            if (!(tableHandle instanceof ImportTableHandle)) {
                // This is pure lazyness. It should be possible to alias any
                // table type but this reduces the amount of code required.
                return node;
            }

            QualifiedTableName tableName = metadata.getTableMetadata(tableHandle).getTable();

            TableAlias tableAlias = aliasDao.getAlias(tableName);
            if (tableAlias == null) {
                return node;
            }

            QualifiedTableName aliasTable = new QualifiedTableName(tableAlias.getDstCatalogName(), tableAlias.getDstSchemaName(), tableAlias.getDstTableName());
            Optional<TableHandle> aliasTableHandle = metadata.getTableHandle(aliasTable);
            if (!aliasTableHandle.isPresent()) {
                return node;
            }

            if (!allNodesPresent(((NativeTableHandle) aliasTableHandle.get()).getTableId())) {
                return node;
            }

            Map<String, ColumnHandle> lookupColumns = metadata.getColumnHandles(aliasTableHandle.get());

            Map<Symbol, ColumnHandle> assignments =  node.getAssignments();

            ImmutableMap.Builder<Symbol, ColumnHandle> newAssignmentsBuilder = ImmutableMap.builder();
            for (Map.Entry<Symbol, ColumnHandle> assignmentEntry : assignments.entrySet()) {
                ColumnMetadata originalColumn = metadata.getColumnMetadata(tableHandle, assignmentEntry.getValue());

                ColumnHandle aliasedColumnHandle = lookupColumns.get(originalColumn.getName());
                checkState(aliasedColumnHandle != null, "no matching column for original column %s found!", originalColumn);
                newAssignmentsBuilder.put(assignmentEntry.getKey(), aliasedColumnHandle);
            }

            return new TableScanNode(node.getId(), aliasTableHandle.get(), newAssignmentsBuilder.build());
        }

        private boolean allNodesPresent(long tableId)
        {
            Set<String> nodesActive = ImmutableSet.copyOf(Collections2.transform(nodeManager.getActiveNodes(), Node.getIdentifierFunction()));
            Set<String> nodesRequired = ImmutableSet.copyOf(shardManager.getCommittedShardNodes(tableId).values());

            return nodesActive.containsAll(nodesRequired);
        }
    }
}