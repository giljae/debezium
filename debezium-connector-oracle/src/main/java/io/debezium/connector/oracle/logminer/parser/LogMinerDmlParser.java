/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer.parser;

import io.debezium.DebeziumException;
import io.debezium.connector.oracle.OracleDatabaseSchema;
import io.debezium.connector.oracle.OracleValueConverters;
import io.debezium.connector.oracle.logminer.LogMinerHelper;
import io.debezium.relational.Column;
import io.debezium.relational.Table;

/**
 * A simple DML parser implementation specifically for Oracle LogMiner.
 *
 * The syntax of each DML operation is restricted to the format generated by Oracle LogMiner.  The
 * following are examples of each expected syntax:
 *
 * <pre>
 *     insert into "schema"."table"("C1","C2") values ('v1','v2');
 *     update "schema"."table" set "C1" = 'v1a', "C2" = 'v2a' where "C1" = 'v1' and "C2" = 'v2';
 *     delete from "schema"."table" where "C1" = 'v1' AND "C2" = 'v2';
 * </pre>
 *
 * Certain data types are not emitted as string literals, such as {@code DATE} and {@code TIMESTAMP}.
 * For these data types, they're emitted as function calls.  The parser can detect this use case and
 * will emit the values for such columns as the explicit function call.
 *
 * Lets take the following {@code UPDATE} statement:
 *
 * <pre>
 *     update "schema"."table"
 *        set "C1" = TO_TIMESTAMP('2020-02-02 00:00:00', 'YYYY-MM-DD HH24:MI:SS')
 *      where "C1" = TO_TIMESTAMP('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS');
 * </pre>
 *
 * The new value for {@code C1} would be {@code TO_TIMESTAMP('2020-02-02 00:00:00', 'YYYY-MM-DD HH24:MI:SS')}.
 * The old value for {@code C1} would be {@code TO_TIMESTAMP('2020-02-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')}.
 *
 * @author Chris Cranford
 */
public class LogMinerDmlParser implements DmlParser {

    private static final String NULL_SENTINEL = "${DBZ_NULL}";
    private static final String NULL = "NULL";
    private static final String INSERT_INTO = "insert into ";
    private static final String UPDATE = "update ";
    private static final String DELETE_FROM = "delete from ";
    private static final String AND = "and ";
    private static final String OR = "or ";
    private static final String SET = " set ";
    private static final String WHERE = " where ";
    private static final String VALUES = " values ";
    private static final String IS_NULL = "IS NULL";
    // Use by Oracle for specific data types that cannot be represented in SQL
    private static final String UNSUPPORTED = "Unsupported";
    private static final String UNSUPPORTED_TYPE = "Unsupported Type";

    private static final int INSERT_INTO_LENGTH = INSERT_INTO.length();
    private static final int UPDATE_LENGTH = UPDATE.length();
    private static final int DELETE_FROM_LENGTH = DELETE_FROM.length();
    private static final int VALUES_LENGTH = VALUES.length();
    private static final int SET_LENGTH = SET.length();
    private static final int WHERE_LENGTH = WHERE.length();

    @Override
    public LogMinerDmlEntry parse(String sql, Table table) {
        if (table == null) {
            throw new DmlParserException("DML parser requires a non-null table");
        }
        if (sql != null && sql.length() > 0) {
            switch (sql.charAt(0)) {
                case 'i':
                    return parseInsert(sql, table);
                case 'u':
                    return parseUpdate(sql, table);
                case 'd':
                    return parseDelete(sql, table);
            }
        }
        throw new DmlParserException("Unknown supported SQL '" + sql + "'");
    }

    /**
     * Parse an {@code INSERT} SQL statement.
     *
     * @param sql the sql statement
     * @param table the table
     * @return the parsed DML entry record or {@code null} if the SQL was not parsed
     */
    private LogMinerDmlEntry parseInsert(String sql, Table table) {
        try {
            // advance beyond "insert into "
            int index = INSERT_INTO_LENGTH;

            // parse table
            index = parseTableName(sql, index);

            // capture column names
            String[] columnNames = new String[table.columns().size()];
            index = parseColumnListClause(sql, index, columnNames);

            // capture values
            Object[] newValues = new Object[table.columns().size()];
            parseColumnValuesClause(sql, index, columnNames, newValues, table);

            return LogMinerDmlEntryImpl.forInsert(newValues);
        }
        catch (Exception e) {
            throw new DmlParserException("Failed to parse insert DML: '" + sql + "'", e);
        }
    }

    /**
     * Parse an {@code UPDATE} SQL statement.
     *
     * @param sql the sql statement
     * @param table the table
     * @return the parsed DML entry record or {@code null} if the SQL was not parsed
     */
    private LogMinerDmlEntry parseUpdate(String sql, Table table) {
        try {
            // advance beyond "update "
            int index = UPDATE_LENGTH;

            // parse table
            index = parseTableName(sql, index);

            // parse set
            Object[] newValues = new Object[table.columns().size()];
            index = parseSetClause(sql, index, newValues, table);

            // parse where
            Object[] oldValues = new Object[table.columns().size()];
            parseWhereClause(sql, index, oldValues, table);

            // For each after state field that is either a NULL_SENTINEL (explicitly wants NULL) or
            // that wasn't specified and therefore remained null, correctly adapt the after state
            // accordingly, leaving any field's after value alone if it isn't null or a sentinel.
            for (int i = 0; i < oldValues.length; ++i) {
                // set unavailable value in the old values if applicable
                oldValues[i] = getColumnUnavailableValue(oldValues[i], table.columns().get(i));
                if (newValues[i] == NULL_SENTINEL) {
                    // field is explicitly set to NULL, clear the sentinel and continue
                    newValues[i] = null;
                }
                else if (newValues[i] == null) {
                    // field wasn't specified in set-clause, copy before state to after state
                    newValues[i] = oldValues[i];
                }
            }

            return LogMinerDmlEntryImpl.forUpdate(newValues, oldValues);
        }
        catch (Exception e) {
            throw new DmlParserException("Failed to parse update DML: '" + sql + "'", e);
        }
    }

    /**
     * Parses a SQL {@code DELETE} statement.
     *
     * @param sql the sql statement
     * @param table the table
     * @return the parsed DML entry record or {@code null} if the SQL was not parsed
     */
    private LogMinerDmlEntry parseDelete(String sql, Table table) {
        try {
            // advance beyond "delete from "
            int index = DELETE_FROM_LENGTH;

            // parse table
            index = parseTableName(sql, index);

            // parse where
            Object[] oldValues = new Object[table.columns().size()];
            parseWhereClause(sql, index, oldValues, table);

            // Check and update unavailable column values
            for (int i = 0; i < oldValues.length; ++i) {
                // set unavailable value in the old values if applicable
                oldValues[i] = getColumnUnavailableValue(oldValues[i], table.columns().get(i));
            }

            return LogMinerDmlEntryImpl.forDelete(oldValues);
        }
        catch (Exception e) {
            throw new DmlParserException("Failed to parse delete DML: '" + sql + "'", e);
        }
    }

    /**
     * Parses a table-name in the SQL clause
     *
     * @param sql the sql statement
     * @param index the index into the sql statement to begin parsing
     * @return the index into the sql string where the table name ended
     */
    private int parseTableName(String sql, int index) {
        boolean inQuote = false;

        for (; index < sql.length(); ++index) {
            char c = sql.charAt(index);
            if (c == '"') {
                if (inQuote) {
                    inQuote = false;
                    continue;
                }
                inQuote = true;
            }
            else if ((c == ' ' || c == '(') && !inQuote) {
                break;
            }
        }

        return index;
    }

    /**
     * Parse an {@code INSERT} statement's column-list clause.
     *
     * @param sql the sql statement
     * @param start the index into the sql statement to begin parsing
     * @param columnNames the list that will be populated with the column names
     * @return the index into the sql string where the column-list clause ended
     */
    private int parseColumnListClause(String sql, int start, String[] columnNames) {
        int index = start;
        boolean inQuote = false;
        int columnIndex = 0;
        for (; index < sql.length(); ++index) {
            char c = sql.charAt(index);
            if (c == '(' && !inQuote) {
                start = index + 1;
            }
            else if (c == ')' && !inQuote) {
                index++;
                break;
            }
            else if (c == '"') {
                if (inQuote) {
                    inQuote = false;
                    columnNames[columnIndex++] = sql.substring(start + 1, index);
                    start = index + 2;
                    continue;
                }
                inQuote = true;
            }
        }
        return index;
    }

    /**
     * Parse an {@code INSERT} statement's column-values clause.
     *
     * @param sql the sql statement
     * @param start the index into the sql statement to begin parsing
     * @param columnNames the column names array, already indexed based on relational table column order
     * @param values the values array that will be populated with column values
     * @param table the relational table
     * @return the index into the sql string where the column-values clause ended
     */
    private int parseColumnValuesClause(String sql, int start, String[] columnNames, Object[] values, Table table) {
        int index = start;
        int nested = 0;
        boolean inQuote = false;
        boolean inValues = false;

        // verify entering values-clause
        if (sql.indexOf(VALUES, index) != index) {
            throw new DebeziumException("Failed to parse DML: " + sql);
        }
        index += VALUES_LENGTH;

        int columnIndex = 0;
        StringBuilder collectedValue = null;
        for (; index < sql.length(); ++index) {
            char c = sql.charAt(index);

            if (inQuote) {
                if (c != '\'') {
                    collectedValue.append(c);
                }
                else {
                    if (sql.charAt(index + 1) == '\'') {
                        collectedValue.append('\'');
                        index = index + 1;
                        continue;
                    }
                }
            }

            if (c == '(' && !inQuote && !inValues) {
                inValues = true;
                start = index + 1;
            }
            else if (c == '(' && !inQuote) {
                nested++;
            }
            else if (c == '\'') {
                if (inQuote) {
                    inQuote = false;
                    continue;
                }
                inQuote = true;
                collectedValue = new StringBuilder();
            }
            else if (!inQuote && (c == ',' || c == ')')) {
                if (c == ')' && nested != 0) {
                    nested--;
                    continue;
                }
                if (c == ',' && nested != 0) {
                    continue;
                }

                if (sql.charAt(start) == '\'' && sql.charAt(index - 1) == '\'') {
                    // value is single-quoted at the start/end, substring without the quotes.
                    int position = LogMinerHelper.getColumnIndexByName(columnNames[columnIndex], table);
                    values[position] = collectedValue.toString();
                    collectedValue = null;
                }
                else {
                    // use value as-is
                    String s = sql.substring(start, index);
                    if (!s.equals(UNSUPPORTED_TYPE) && !s.equals(NULL)) {
                        int position = LogMinerHelper.getColumnIndexByName(columnNames[columnIndex], table);
                        values[position] = s;
                    }
                }

                columnIndex++;
                start = index + 1;
            }
        }

        return index;

    }

    /**
     * Parse an {@code UPDATE} statement's {@code SET} clause.
     *
     * @param sql the sql statement
     * @param start the index into the sql statement to begin parsing
     * @param newValues the new values array to be populated
     * @param table the relational table
     * @return the index into the sql string where the set-clause ended
     */
    private int parseSetClause(String sql, int start, Object[] newValues, Table table) {
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean inColumnName = true;
        boolean inColumnValue = false;
        boolean inSpecial = false;
        int nested = 0;

        // verify entering set-clause
        int set = sql.indexOf(SET, start);
        if (set == -1) {
            throw new DebeziumException("Failed to parse DML: " + sql);
        }
        else if (set != start) {
            // find table alias
            start = set;
        }
        start += SET_LENGTH;

        int index = start;
        String currentColumnName = null;
        StringBuilder collectedValue = null;
        for (; index < sql.length(); ++index) {
            char c = sql.charAt(index);
            char lookAhead = (index + 1 < sql.length()) ? sql.charAt(index + 1) : 0;

            if (inSingleQuote) {
                if (c != '\'') {
                    collectedValue.append(c);
                }
                else {
                    if (lookAhead == '\'') {
                        collectedValue.append('\'');
                        index = index + 1;
                        continue;
                    }
                }
            }

            if (c == '"' && inColumnName) {
                // Set clause column names are double-quoted
                if (inDoubleQuote) {
                    inDoubleQuote = false;
                    currentColumnName = sql.substring(start + 1, index);
                    start = index + 1;
                    inColumnName = false;
                    continue;
                }
                inDoubleQuote = true;
                start = index;
            }
            else if (c == '=' && !inColumnName && !inColumnValue) {
                inColumnValue = true;
                // Oracle SQL generated is always ' = ', skipping following space
                index += 1;
                start = index + 1;
            }
            else if (nested == 0 & c == ' ' && lookAhead == '|') {
                // Possible concatenation, nothing to do yet
            }
            else if (nested == 0 & c == '|' && lookAhead == '|') {
                // Concatenation
                for (int i = index + 2; i < sql.length(); ++i) {
                    if (sql.charAt(i) != ' ') {
                        // found next non-whitespace character
                        index = i - 1;
                        break;
                    }
                }
            }
            else if (c == '\'' && inColumnValue) {
                // Skip over double single quote
                if (inSingleQuote && lookAhead == '\'') {
                    index += 1;
                    continue;
                }
                // Set clause single-quoted column value
                if (inSingleQuote) {
                    inSingleQuote = false;
                    if (nested == 0) {
                        int position = LogMinerHelper.getColumnIndexByName(currentColumnName, table);
                        newValues[position] = collectedValue.toString();
                        collectedValue = null;
                        start = index + 1;
                        inColumnValue = false;
                        inColumnName = false;
                    }
                    continue;
                }
                if (!inSpecial) {
                    start = index;
                }
                inSingleQuote = true;
                collectedValue = new StringBuilder();
            }
            else if (c == ',' && !inColumnValue && !inColumnName) {
                // Set clause uses ', ' skip following space
                inColumnName = true;
                index += 1;
                start = index;
            }
            else if (inColumnValue && !inSingleQuote) {
                if (!inSpecial) {
                    start = index;
                    inSpecial = true;
                }
                // characters as a part of the value
                if (c == '(') {
                    nested++;
                }
                else if (c == ')' && nested > 0) {
                    nested--;
                }
                else if ((c == ',' || c == ' ' || c == ';') && nested == 0) {
                    String value = sql.substring(start, index);
                    if (value.equals(NULL) || value.equals(UNSUPPORTED_TYPE)) {
                        if (value.equals(NULL)) {
                            // In order to identify when a field is not present in the set-clause or when
                            // a field is explicitly set to null, the NULL_SENTINEL value is used to then
                            // indicate that the field is explicitly being cleared to NULL.
                            // This sentinel value will be cleared later when we reconcile before/after
                            // state in parseUpdate()
                            int position = LogMinerHelper.getColumnIndexByName(currentColumnName, table);
                            newValues[position] = NULL_SENTINEL;
                        }
                        start = index + 1;
                        inColumnValue = false;
                        inSpecial = false;
                        inColumnName = true;
                        continue;
                    }
                    else if (value.equals(UNSUPPORTED)) {
                        continue;
                    }
                    int position = LogMinerHelper.getColumnIndexByName(currentColumnName, table);
                    newValues[position] = value;
                    start = index + 1;
                    inColumnValue = false;
                    inSpecial = false;
                    inColumnName = true;
                }
            }
            else if (!inDoubleQuote && !inSingleQuote) {
                if (c == 'w' && lookAhead == 'h' && sql.indexOf(WHERE, index - 1) == index - 1) {
                    index -= 1;
                    break;
                }
            }
        }

        return index;
    }

    /**
     * Parses a {@code WHERE} clause populates the provided column names and values arrays.
     *
     * @param sql the sql statement
     * @param start the index into the sql statement to begin parsing
     * @param values the column values to be parsed from the where clause
     * @param table the relational table
     * @return the index into the sql string to continue parsing
     */
    private int parseWhereClause(String sql, int start, Object[] values, Table table) {
        int nested = 0;
        boolean inColumnName = true;
        boolean inColumnValue = false;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean inSpecial = false;

        // DBZ-3235
        // LogMiner can generate SQL without a WHERE condition under some circumstances and if it does
        // we shouldn't immediately fail DML parsing.
        if (start >= sql.length()) {
            return start;
        }

        // verify entering where-clause
        int where = sql.indexOf(WHERE, start);
        if (where == -1) {
            throw new DebeziumException("Failed to parse DML: " + sql);
        }
        else if (where != start) {
            // find table alias
            start = where;
        }
        start += WHERE_LENGTH;

        int index = start;
        String currentColumnName = null;
        StringBuilder collectedValue = null;
        for (; index < sql.length(); ++index) {
            char c = sql.charAt(index);
            char lookAhead = (index + 1 < sql.length()) ? sql.charAt(index + 1) : 0;
            if (inSingleQuote) {
                if (c != '\'') {
                    collectedValue.append(c);
                }
                else {
                    if (lookAhead == '\'') {
                        collectedValue.append('\'');
                        index = index + 1;
                        continue;
                    }
                }
            }
            if (c == '"' && inColumnName) {
                // Where clause column names are double-quoted
                if (inDoubleQuote) {
                    inDoubleQuote = false;
                    currentColumnName = sql.substring(start + 1, index);
                    start = index + 1;
                    inColumnName = false;
                    continue;
                }
                inDoubleQuote = true;
                start = index;
            }
            else if (c == '=' && !inColumnName && !inColumnValue) {
                inColumnValue = true;
                // Oracle SQL generated is always ' = ', skipping following space
                index += 1;
                start = index + 1;
            }
            else if (c == 'I' && !inColumnName && !inColumnValue) {
                if (sql.indexOf(IS_NULL, index) == index) {
                    index += 6;
                    start = index;
                    continue;
                }
            }
            else if (c == '\'' && inColumnValue) {
                // Skip over double single quote
                if (inSingleQuote && lookAhead == '\'') {
                    index += 1;
                    continue;
                }
                // Where clause single-quoted column value
                if (inSingleQuote) {
                    inSingleQuote = false;
                    if (nested == 0) {
                        int position = LogMinerHelper.getColumnIndexByName(currentColumnName, table);
                        values[position] = collectedValue.toString();
                        collectedValue = null;
                        start = index + 1;
                        inColumnValue = false;
                        inColumnName = false;
                    }
                    continue;
                }
                if (!inSpecial) {
                    start = index;
                }
                inSingleQuote = true;
                collectedValue = new StringBuilder();
            }
            else if (inColumnValue && !inSingleQuote) {
                if (!inSpecial) {
                    start = index;
                    inSpecial = true;
                }
                if (c == '(') {
                    nested++;
                }
                else if (c == ')' && nested > 0) {
                    nested--;
                }
                else if (nested == 0 & c == ' ' && lookAhead == '|') {
                    // Possible concatenation, nothing to do yet
                }
                else if (nested == 0 & c == '|' && lookAhead == '|') {
                    // Concatenation
                    for (int i = index + 2; i < sql.length(); ++i) {
                        if (sql.charAt(i) != ' ') {
                            // found next non-whitespace character
                            index = i - 1;
                            break;
                        }
                    }
                }
                else if ((c == ';' || c == ' ') && nested == 0) {
                    String value = sql.substring(start, index);
                    if (value.equals(NULL) || value.equals(UNSUPPORTED_TYPE)) {
                        start = index + 1;
                        inColumnValue = false;
                        inSpecial = false;
                        inColumnName = true;
                        continue;
                    }
                    else if (value.equals(UNSUPPORTED)) {
                        continue;
                    }
                    int position = LogMinerHelper.getColumnIndexByName(currentColumnName, table);
                    values[position] = value;
                    start = index + 1;
                    inColumnValue = false;
                    inSpecial = false;
                    inColumnName = true;
                }
            }
            else if (!inColumnValue && !inColumnName) {
                if (c == 'a' && lookAhead == 'n' && sql.indexOf(AND, index) == index) {
                    index += 3;
                    start = index;
                    inColumnName = true;
                }
                else if (c == 'o' && lookAhead == 'r' && sql.indexOf(OR, index) == index) {
                    index += 2;
                    start = index;
                    inColumnName = true;
                }
            }
        }

        return index;
    }

    private Object getColumnUnavailableValue(Object value, Column column) {
        if (value == null && OracleDatabaseSchema.isLobColumn(column)) {
            return OracleValueConverters.UNAVAILABLE_VALUE;
        }
        return value;
    }
}
