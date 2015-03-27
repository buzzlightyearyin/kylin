/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.job.hadoop.cube;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hive.hcatalog.data.HCatRecord;
import org.apache.hive.hcatalog.data.schema.HCatFieldSchema;
import org.apache.hive.hcatalog.data.schema.HCatSchema;
import org.apache.hive.hcatalog.mapreduce.HCatInputFormat;
import org.apache.kylin.common.hll.HyperLogLogPlusCounter;
import org.apache.kylin.cube.cuboid.CuboidScheduler;
import org.apache.kylin.cube.model.CubeJoinedFlatTableDesc;
import org.apache.kylin.dict.lookup.HiveTableReader;
import org.apache.kylin.job.constant.BatchConstants;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author yangli9
 */
public class FactDistinctHiveColumnsMapper<KEYIN> extends FactDistinctColumnsMapperBase<KEYIN, HCatRecord> {

    private HCatSchema schema = null;
    private CubeJoinedFlatTableDesc intermediateTableDesc;

    protected boolean collectStatistics = false;
    protected CuboidScheduler cuboidScheduler = null;
    protected List<String> rowKeyValues = null;
    protected Map<Long, HyperLogLogPlusCounter> cuboidHLLMap = null;
    protected HyperLogLogPlusCounter totalHll = null;
    protected int nRowKey;

    @Override
    protected void setup(Context context) throws IOException {
        super.setup(context);

        schema = HCatInputFormat.getTableSchema(context.getConfiguration());
        intermediateTableDesc = new CubeJoinedFlatTableDesc(cubeDesc, null);

        collectStatistics = Boolean.parseBoolean(context.getConfiguration().get(BatchConstants.CFG_STATISTICS_ENABLED));
        if (collectStatistics) {
            cuboidScheduler = new CuboidScheduler(cubeDesc);
            cuboidHLLMap = Maps.newHashMap();
            totalHll = new HyperLogLogPlusCounter(16);
            rowKeyValues = Lists.newArrayList();
            nRowKey = cubeDesc.getRowkey().getRowKeyColumns().length;
        }
    }

    @Override
    public void map(KEYIN key, HCatRecord record, Context context) throws IOException, InterruptedException {
        try {
            int[] flatTableIndexes = intermediateTableDesc.getRowKeyColumnIndexes();
            HCatFieldSchema fieldSchema;
            for (int i : factDictCols) {
                outputKey.set((long) i);
                fieldSchema = schema.get(flatTableIndexes[i]);
                Object fieldValue = record.get(fieldSchema.getName(), schema);
                if (fieldValue == null)
                    continue;
                byte[] bytes = Bytes.toBytes(fieldValue.toString());
                outputValue.set(bytes, 0, bytes.length);
                context.write(outputKey, outputValue);
            }
        } catch (Exception ex) {
            handleErrorRecord(record, ex);
        }

        if (collectStatistics) {
            String[] row = HiveTableReader.getRowAsStringArray(record);
            putRowKeyToHLL(row, baseCuboidId);
        }
    }

    private void putRowKeyToHLL(String[] row, long cuboidId) {
        rowKeyValues.clear();
        long mask = Long.highestOneBit(baseCuboidId);
        for (int i = 0; i < nRowKey; i++) {
            if ((mask & cuboidId) == 1) {
                rowKeyValues.add(row[intermediateTableDesc.getRowKeyColumnIndexes()[i]]);
            }
            mask = mask >> 1;
        }

        HyperLogLogPlusCounter hll = cuboidHLLMap.get(cuboidId);
        if (hll == null) {
            hll = new HyperLogLogPlusCounter(16);
            cuboidHLLMap.put(cuboidId, hll);
        }

        hll.add(StringUtils.join(rowKeyValues, ","));

        Collection<Long> children = cuboidScheduler.getSpanningCuboid(cuboidId);
        for (Long childId : children) {
            putRowKeyToHLL(row, childId);
        }

    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (collectStatistics) {

            // output each cuboid's hll to reducer, key is 0 - cuboidId
            for (Long cuboidId : cuboidHLLMap.keySet()) {
                HyperLogLogPlusCounter hll = cuboidHLLMap.get(cuboidId);
                totalHll.merge(hll); // merge each cuboid's counter to the total hll
                outputKey.set(0 - cuboidId);
                ByteBuffer hllBuf = ByteBuffer.allocate(64 * 1024);
                hll.writeRegisters(hllBuf);
                outputValue.set(hllBuf.array());
                context.write(outputKey, outputValue);
            }

            //output the total hll for this mapper;
            outputKey.set(0 - baseCuboidId - 1);
            ByteBuffer hllBuf = ByteBuffer.allocate(64 * 1024);
            totalHll.writeRegisters(hllBuf);
            outputValue.set(hllBuf.array());
            context.write(outputKey, outputValue);
        }
    }

}
