/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.kudu.flume.sink;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.Type;
import org.apache.kudu.annotations.InterfaceAudience;
import org.apache.kudu.annotations.InterfaceStability;
import org.apache.kudu.client.Insert;
import org.apache.kudu.client.KuduTable;
import org.apache.kudu.client.Operation;
import org.apache.kudu.client.PartialRow;
import org.apache.kudu.client.Upsert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * A splitter operations producer that generates one or more Kudu
 * {@link Insert} or {@link Upsert} operations per Flume {@link Event} by
 * parsing the event {@code body} using a splitter. Values are coerced
 * to the types of the named columns in the Kudu table.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class SplitterKuduOperationsProducer implements KuduOperationsProducer {
	private static final Logger logger = LoggerFactory.getLogger(SplitterKuduOperationsProducer.class);
	private static final String INSERT = "insert";
	private static final String UPSERT = "upsert";
	private static final List<String> validOperations = Lists.newArrayList(UPSERT, INSERT);

	public static final String FIELDS_PROP = "fields";
	public static final String DELIMIT_PROP = "delimited";
	public static final String ENCODING_PROP = "encoding";
	public static final String DEFAULT_ENCODING = "utf-8";
	public static final String OPERATION_PROP = "operation";
	public static final String DEFAULT_OPERATION = UPSERT;
	public static final String SKIP_MISSING_COLUMN_PROP = "skipMissingColumn";
	public static final boolean DEFAULT_SKIP_MISSING_COLUMN = false;
	public static final String SKIP_BAD_COLUMN_VALUE_PROP = "skipBadColumnValue";
	public static final boolean DEFAULT_SKIP_BAD_COLUMN_VALUE = false;
	public static final String WARN_UNMATCHED_ROWS_PROP = "skipUnmatchedRows";
	public static final boolean DEFAULT_WARN_UNMATCHED_ROWS = true;

	private KuduTable table;
	private Charset charset;
	private String operation;
	private String delimited;
	private List<String> fields;
	private boolean skipMissingColumn;
	private boolean skipBadColumnValue;

	public SplitterKuduOperationsProducer() {
	}

	@Override
	public void configure(Context context) {
		String fieldprop = context.getString(FIELDS_PROP);
		logger.info(String.format("fields: %s", fieldprop));
		Preconditions.checkArgument(fieldprop != null, "Required parameter %s is not specified", FIELDS_PROP);
		delimited = context.getString(DELIMIT_PROP, ",");
		logger.info(String.format("delimited: %s", delimited));
		Preconditions.checkArgument(delimited != null, "Required parameter %s is not specified", DELIMIT_PROP);
		fields = Arrays.asList(fieldprop.trim().toLowerCase().split(","));
		logger.info(String.format("fields length: %d", fields.size()));
		String charsetName = context.getString(ENCODING_PROP, DEFAULT_ENCODING);
		try {
			charset = Charset.forName(charsetName);
		} catch (IllegalArgumentException e) {
			throw new FlumeException(String.format("Invalid or unsupported charset %s", charsetName), e);
		}
		operation = context.getString(OPERATION_PROP, DEFAULT_OPERATION).toLowerCase();
		Preconditions.checkArgument(validOperations.contains(operation), "Unrecognized operation '%s'", operation);
		skipMissingColumn = context.getBoolean(SKIP_MISSING_COLUMN_PROP, DEFAULT_SKIP_MISSING_COLUMN);
		skipBadColumnValue = context.getBoolean(SKIP_BAD_COLUMN_VALUE_PROP, DEFAULT_SKIP_BAD_COLUMN_VALUE);
	}

	@Override
	public void initialize(KuduTable table) {
		this.table = table;
	}

	@Override
	public List<Operation> getOperations(Event event) throws FlumeException {
		String raw = new String(event.getBody(), charset);
		String[] fs = raw.split(delimited,-1);
		Schema schema = table.getSchema();
		List<Operation> ops = Lists.newArrayList();

		Operation op;
		switch (operation) {
		case UPSERT:
			op = table.newUpsert();
			break;
		case INSERT:
			op = table.newInsert();
			break;
		default:
			throw new FlumeException(String.format(
					"Unrecognized operation type '%s' in getOperations(): " + "this should never happen!", operation));
		}
		PartialRow row = op.getRow();
		for (ColumnSchema col : schema.getColumns()) {
			try {
				String colName = col.getName();

				if (fields.contains(colName)) {
					int index = fields.indexOf(colName);
					coerceAndSet(fs[index], colName, col.getType(), row);
				}
			} catch (NumberFormatException e) {
				String msg = String.format("Raw value '%s' couldn't be parsed to type %s for column '%s'", raw,
						col.getType(), col.getName());
				logOrThrow(skipBadColumnValue, msg, e);
			} catch (IllegalArgumentException e) {
				String msg = String.format("Column '%s' has no matching group in '%s'", col.getName(), raw);
				logOrThrow(skipMissingColumn, msg, e);
			} catch (Exception e) {
				throw new FlumeException("Failed to create Kudu operation", e);
			}
		}
		ops.add(op);

		return ops;
	}

	/**
	 * Coerces the string `rawVal` to the type `type` and sets the resulting
	 * value for column `colName` in `row`.
	 *
	 * @param rawVal
	 *            the raw string column value
	 * @param colName
	 *            the name of the column
	 * @param type
	 *            the Kudu type to convert `rawVal` to
	 * @param row
	 *            the row to set the value in
	 * @throws NumberFormatException
	 *             if `rawVal` cannot be cast as `type`.
	 */
	private void coerceAndSet(String rawVal, String colName, Type type, PartialRow row) throws NumberFormatException {
		switch (type) {
		case INT8:
			row.addByte(colName, Byte.parseByte(rawVal));
			break;
		case INT16:
			row.addShort(colName, Short.parseShort(rawVal));
			break;
		case INT32:
			row.addInt(colName, Integer.parseInt(rawVal));
			break;
		case INT64:
			row.addLong(colName, Long.parseLong(rawVal));
			break;
		case BINARY:
			row.addBinary(colName, rawVal.getBytes(charset));
			break;
		case STRING:
			row.addString(colName, rawVal);
			break;
		case BOOL:
			row.addBoolean(colName, Boolean.parseBoolean(rawVal));
			break;
		case FLOAT:
			row.addFloat(colName, Float.parseFloat(rawVal));
			break;
		case DOUBLE:
			row.addDouble(colName, Double.parseDouble(rawVal));
			break;
		case UNIXTIME_MICROS:
			row.addLong(colName, Long.parseLong(rawVal));
			break;
		default:
			logger.warn("got unknown type {} for column '{}'-- ignoring this column", type, colName);
		}
	}

	private void logOrThrow(boolean log, String msg, Exception e) throws FlumeException {
		if (log) {
			logger.warn(msg, e);
		} else {
			throw new FlumeException(msg, e);
		}
	}

	@Override
	public void close() {
	}
}
