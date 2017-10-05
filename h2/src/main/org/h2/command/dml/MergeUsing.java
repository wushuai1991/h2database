/*
 * Copyright 2004-2017 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import org.h2.api.ErrorCode;
import org.h2.api.Trigger;
import org.h2.command.Prepared;
import org.h2.engine.Right;
import org.h2.engine.Session;
import org.h2.engine.UndoLogRecord;
import org.h2.expression.ConditionAndOr;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionVisitor;
import org.h2.expression.Parameter;
import org.h2.index.Index;
import org.h2.message.DbException;
import org.h2.result.ResultInterface;
import org.h2.result.Row;
import org.h2.result.RowImpl;
import org.h2.table.Column;
import org.h2.table.PlanItem;
import org.h2.table.TableFilter;
import org.h2.table.TableView;
import org.h2.util.StatementBuilder;
import org.h2.value.Value;

/**
 * This class represents the statement syntax
 * MERGE table alias USING...
 */
public class MergeUsing extends Merge {
    
    private TableFilter sourceTableFilter;
    private Expression onCondition;
    private Update updateCommand;
    private Delete deleteCommand;
    private Insert insertCommand;
    private String queryAlias;
    private TableView temporarySourceTableView;
    private int countUpdatedRows=0;

    public MergeUsing(Merge merge) {
        super(merge.getSession());
        
        // bring across only the already parsed data from Merge...
        this.targetTable = merge.targetTable;
        this.targetTableFilter = merge.targetTableFilter;

    }

  
    @Override
    public int update() {
        System.out.println("update using:"+temporarySourceTableView);
        
        if(targetTableFilter!=null){
            targetTableFilter.startQuery(session);
            targetTableFilter.reset();
        }
        
        if(sourceTableFilter!=null){
            sourceTableFilter.startQuery(session);
            sourceTableFilter.reset();
        }
       
        int countInputRows;
        checkRights();
        setCurrentRowNumber(0);

        // process select query data for row creation
        ResultInterface rows = query.query(0);
        countInputRows = 0;
        targetTable.fire(session, evaluateTriggerMasks(), true);
        targetTable.lock(session, true, false);
        while (rows.next()) {
            countInputRows++;
            Value[] sourceRowValues = rows.currentRow();
            Row sourceRow = new RowImpl(sourceRowValues,0);
            System.out.println(("currentRowValues="+Arrays.toString(sourceRowValues)));
            Row newTargetRow = targetTable.getTemplateRow();
            setCurrentRowNumber(countInputRows);
            System.out.println("columns="+Arrays.toString(columns));
            
            // computer the new target row columns values
            for (int j = 0; j < columns.length; j++) {
                Column c = columns[j];
                int index = c.getColumnId();
                try {
                    Value v = c.convert(sourceRowValues[j]);
                    newTargetRow.setValue(index, v);
                } catch (DbException ex) {
                    throw setRow(ex, countInputRows, getSQL(sourceRowValues));
                }
            }
            merge(sourceRow, sourceRowValues,newTargetRow);
        }
        rows.close();
        targetTable.fire(session, evaluateTriggerMasks(), false);
        return countUpdatedRows;
    }


    private int evaluateTriggerMasks() {
        int masks = 0;
        if(insertCommand!=null){
            masks |= Trigger.INSERT;
        }
        if(updateCommand!=null){
            masks |= Trigger.UPDATE;
        }
        if(deleteCommand!=null){
            masks |= Trigger.DELETE;
        }
        return masks;
    }

    private void checkRights() {
        if(insertCommand!=null){
            session.getUser().checkRight(targetTable, Right.INSERT);
        }
        if(updateCommand!=null){
            session.getUser().checkRight(targetTable, Right.UPDATE);
        }
        if(deleteCommand!=null){
            session.getUser().checkRight(targetTable, Right.DELETE);
        }
    }

    protected void merge(Row sourceRow, Value[] sourceRowValues, Row newTargetRow) {
        configPreparedParameters(newTargetRow, update);

        // put the column values into the table filter
        sourceTableFilter.set(sourceRow);

        // try and perform an update
        int count = 0;
        System.out.println("onConditions="+onCondition.toString());
        if(updateCommand!=null){
            System.out.println("updatePlanSQL="+updateCommand.getPlanSQL());
            count += updateCommand.update();
            System.out.println("update.count="+count);
        }
        if(deleteCommand!=null && count==0){
            System.out.println("deleteCommand="+deleteCommand.getPlanSQL());
            count += deleteCommand.update();
            System.out.println("delete.count="+count);
        }
        
        // if either updates do nothing, try an insert
        if (count == 0) {
            count+=addRowByCommandInsert(session,newTargetRow);
            //addRowByAPIInsert(session,newTargetRow);
        } else if (count != 1) {
            throw DbException.get(ErrorCode.DUPLICATE_KEY_1, targetTable.getSQL());
        }
        countUpdatedRows+=count;
    }


    private void configPreparedParameters(Row newTargetRow, Prepared updatePrepared) {
        ArrayList<Parameter> k = updatePrepared.getParameters();
        // set each parameter in the updatePrepared with the real value from the source column
        // 0 to columns.length-1
        for (int i = 0; i < columns.length; i++) {
            Column col = columns[i];
            Value v = newTargetRow.getValue(col.getColumnId());
            Parameter p = k.get(i);
            p.setValue(v);
        }
        // columns.length to columns.length+keys.length-1
        for (int i = 0; i < keys.length; i++) {
            Column col = keys[i];
            Value v = newTargetRow.getValue(col.getColumnId());
            if (v == null) {
                throw DbException.get(ErrorCode.COLUMN_CONTAINS_NULL_VALUES_1, col.getSQL());
            }
            Parameter p = k.get(columns.length + i);
            p.setValue(v);
        }
    }
    
    private int addRowByCommandInsert(Session session, Row newTargetRow) {
        int localCount = 0;
        if(insertCommand!=null){
            System.out.println("insertPlanSQL="+insertCommand.getPlanSQL());
            localCount += insertCommand.update();
        }
        System.out.println("insert.count="+localCount);
        return localCount;
    }

    private int addRowByAPIInsert(Session session, Row newTargetRow) {
        System.out.println("addRowByInsert=(hashcode)"+newTargetRow.hashCode());        
        try {
            targetTable.validateConvertUpdateSequence(session, newTargetRow);
            boolean done = targetTable.fireBeforeRow(session, null, newTargetRow);
            if (!done) {
                targetTable.lock(session, true, false);
                targetTable.addRow(session, newTargetRow);
                session.log(targetTable, UndoLogRecord.INSERT, newTargetRow);
                targetTable.fireAfterRow(session, null, newTargetRow, false);
                return 1;
            }
            return 0;
        } catch (DbException e) {
            if (e.getErrorCode() == ErrorCode.DUPLICATE_KEY_1) {
                // possibly a concurrent merge or insert
                Index index = (Index) e.getSource();
                if (index != null) {
                    // verify the index columns match the key
                    Column[] indexColumns = index.getColumns();
                    boolean indexMatchesKeys = true;
                    if (indexColumns.length <= keys.length) {
                        for (int i = 0; i < indexColumns.length; i++) {
                            if (indexColumns[i] != keys[i]) {
                                indexMatchesKeys = false;
                                break;
                            }
                        }
                    }
                    if (indexMatchesKeys) {
                        throw DbException.get(ErrorCode.CONCURRENT_UPDATE_1, targetTable.getName());
                    }
                }
            }
            throw e;
        }
    }


    @Override
    public String getPlanSQL() {
        System.out.println("getPlanSQL");

        StatementBuilder buff = new StatementBuilder("MERGE INTO ");
        buff.append(targetTable.getSQL()).append('(');
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL());
        }
        buff.append(')');
        if (keys != null) {
            buff.append(" KEY(");
            buff.resetCount();
            for (Column c : keys) {
                buff.appendExceptFirst(", ");
                buff.append(c.getSQL());
            }
            buff.append(')');
        }
        buff.append('\n');
        if (valuesExpressionList.size() > 0) {
            buff.append("VALUES ");
            int row = 0;
            for (Expression[] expr : valuesExpressionList) {
                if (row++ > 0) {
                    buff.append(", ");
                }
                buff.append('(');
                buff.resetCount();
                for (Expression e : expr) {
                    buff.appendExceptFirst(", ");
                    if (e == null) {
                        buff.append("DEFAULT");
                    } else {
                        buff.append(e.getSQL());
                    }
                }
                buff.append(')');
            }
        } else {
            buff.append(query.getPlanSQL());
        }
        return buff.toString();
    }

/*    
    @Override
    public void prepare() {
        if (condition != null) {
            condition.mapColumns(tableFilter, 0);
            condition = condition.optimize(session);
            condition.createIndexConditions(session, tableFilter);
        }
        for (int i = 0, size = columns.size(); i < size; i++) {
            Column c = columns.get(i);
            Expression e = expressionMap.get(c);
            e.mapColumns(tableFilter, 0);
            expressionMap.put(c, e.optimize(session));
        }
        TableFilter[] filters = new TableFilter[] { tableFilter };
        PlanItem item = tableFilter.getBestPlanItem(session, filters, 0,
                ExpressionVisitor.allColumnsForTableFilters(filters));
        tableFilter.setPlanItem(item);
        tableFilter.prepare();
    }
*/    
    
    /*
        MERGE INTO targetTableName [[AS] t_alias] USING table_reference [[AS} s_alias] ON ( condition ,...)
            WHEN MATCHED THEN
             [UPDATE SET column1 = value1 [, column2 = value2 ... WHERE ...] 
             [DELETE WHERE ...]
            WHEN NOT MATCHED THEN
             INSERT (column1 [, column2 ...]) VALUES (value1 [, value2 ...]);
            
            table_reference ::= table | view | ( sub-query )
            
         The current implementation (for comparison) uses this syntax:
         
          MERGE INTO tablename [(columnName1,...)] 
          [KEY (keyColumnName1,...)]
          [ VALUES (expression1,...) | SELECT ...]           
    */    
    @Override
    public void prepare() {
        System.out.println("prepare:targetTableFilterAlias="+targetTableFilter.getTableAlias());
        System.out.println("prepare:sourceTableFilterAlias="+sourceTableFilter.getTableAlias());
        System.out.println("prepare:onConditions="+onCondition);
        
        TableFilter[] filters = new TableFilter[] { sourceTableFilter, targetTableFilter };
        
        System.out.println("onCondition="+onCondition+":op="+onCondition.getClass().getSimpleName());

        onCondition.addFilterConditions(sourceTableFilter, true);
        onCondition.addFilterConditions(targetTableFilter, true);
        onCondition.mapColumns(sourceTableFilter, 2);
        onCondition.mapColumns(targetTableFilter, 1);
        onCondition = onCondition.optimize(session);
        onCondition.createIndexConditions(session, sourceTableFilter);
        //optional
        onCondition.createIndexConditions(session, targetTableFilter);

        if (columns == null) {
            if (valuesExpressionList.size() > 0 && valuesExpressionList.get(0).length == 0) {
                // special case where table is used as a sequence
                columns = new Column[0];
            } else {
                columns = targetTable.getColumns();
            }
        }
        if (valuesExpressionList.size() > 0) {
            for (Expression[] expr : valuesExpressionList) {
                if (expr.length != columns.length) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
                for (int i = 0; i < expr.length; i++) {
                    Expression e = expr[i];
                    if (e != null) {
                        expr[i] = e.optimize(session);
                    }
                }
            }
        } else {
            query.prepare();
            if (query.getColumnCount() != columns.length) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
//        if (keys == null) {
//            Index idx = targetTable.getPrimaryKey();
//            if (idx == null) {
//                throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, "PRIMARY KEY");
//            }
//            keys = idx.getColumns();
//        }
        if (keys == null) {
            HashSet<Column> targetColumns = new HashSet<Column>();
            HashSet<Column> columns = new HashSet<Column>();
            ExpressionVisitor visitor = ExpressionVisitor.getColumnsVisitor(columns);
            onCondition.isEverything(visitor);
            for(Column c: columns){
                if(c.getTable()==targetTable){
                    targetColumns.add(c);
                }
            }
            if (targetColumns.isEmpty()) {
                throw DbException.get(ErrorCode.CONSTRAINT_NOT_FOUND_1, "ON (condition) target columns missing");
            }
            keys = targetColumns.toArray(new Column[1]);
        }        
        String sql = buildPreparedSQL();
        update = session.prepare(sql);
        
        // Not sure how these sub-prepares will work...
        if(updateCommand!=null){
            updateCommand.setSourceTableFilter(sourceTableFilter);
            updateCommand.setCondition(appendOnCondition(updateCommand));
            updateCommand.prepare();
        }
        if(deleteCommand!=null){
            deleteCommand.setSourceTableFilter(sourceTableFilter);
            deleteCommand.setCondition(appendOnCondition(deleteCommand));            
            deleteCommand.prepare();
        }        
        if(insertCommand!=null){
            insertCommand.setSourceTableFilter(sourceTableFilter);
            insertCommand.prepare();
        }        
        
        
    }

    private Expression appendOnCondition(Update updateCommand) {
        if (updateCommand.getCondition()==null){
            return onCondition;
        }
        return new ConditionAndOr(ConditionAndOr.AND,updateCommand.getCondition(),onCondition);
    }

    private Expression appendOnCondition(Delete deleteCommand) {
        if (deleteCommand.getCondition()==null){
            return onCondition;
        }
        return new ConditionAndOr(ConditionAndOr.AND,deleteCommand.getCondition(),onCondition);
    }
    
    private String buildPreparedSQL() {
        StatementBuilder buff = new StatementBuilder("UPDATE ");
        buff.append(targetTable.getSQL());
        if(targetTableFilter.getTableAlias()!=null){
            buff.append(" AS "+targetTableFilter.getTableAlias()+" ");            
        }
        buff.append(" SET ");
        for (Column c : columns) {
            buff.appendExceptFirst(", ");
            buff.append(c.getSQL()).append("=?");
        }
        buff.append(" WHERE ");
        buff.resetCount();
        for (Column c : keys) {
            buff.appendExceptFirst(" AND ");
            buff.append(c.getSQL()).append("=?");
        }
        String sql = buff.toString();
        return sql;
    }
    private String buildPreparedSQLForMergeUsing() {
        StatementBuilder buff = new StatementBuilder("MERGE INTO ");
        buff.append(targetTable.getSQL());
        if(targetTableFilter.getTableAlias()!=null){
            buff.append(" AS "+targetTableFilter.getTableAlias()+"\n");            
        }
        buff.append("USING \n");
        buff.append(temporarySourceTableView.getSQL());
        buff.append("\n");
        if(sourceTableFilter.getTableAlias()!=null){
            buff.append(" AS "+sourceTableFilter.getTableAlias()+"\n");            
        }
        buff.append("ON (");
        buff.append(onCondition.getSQL());
        buff.append(" )");
        if(updateCommand!=null || deleteCommand!=null){
            buff.append("\nWHEN MATCHED\n");
            if(updateCommand!=null){
                buff.append(updateCommand.getPlanSQL());
            }
            if(deleteCommand!=null){
                buff.append(deleteCommand.getPlanSQL());
            }
        }
        if(insertCommand!=null){
            buff.append("\nWHEN NOT MATCHED\n");
            if(insertCommand!=null){
                buff.append(insertCommand.getPlanSQL());
            }
        }
//        buff.resetCount();
//        for (Column c : keys) {
//            buff.appendExceptFirst(" AND ");
//            buff.append(c.getSQL()).append("=?");
//        }
        String sql = buff.toString();
        return sql;
    }
    
    public void setSourceTableFilter(TableFilter sourceTableFilter) {
        this.sourceTableFilter = sourceTableFilter;        
    }

    public void setOnCondition(Expression condition) {
        this.onCondition = condition;        
    }
    
    public Prepared getUpdateCommand() {
        return updateCommand;
    }

    public void setUpdateCommand(Update updateCommand) {
        this.updateCommand = updateCommand;
    }
    
    public Prepared getDeleteCommand() {
        return deleteCommand;
    }

    public void setDeleteCommand(Delete deleteCommand) {
        this.deleteCommand = deleteCommand;
    }
    
    public Insert getInsertCommand() {
        return insertCommand;
    }

    public void setInsertCommand(Insert insertCommand) {
        this.insertCommand = insertCommand;
    }

    public void setQueryAlias(String alias) {
        this.queryAlias = alias;

    }
    public String getQueryAlias() {
        return this.queryAlias;

    }
    public Query getQuery() {
        return query;
    }

    public void setTemporaryTableView(TableView temporarySourceTableView) {
        this.temporarySourceTableView = temporarySourceTableView;        
    }  
}
