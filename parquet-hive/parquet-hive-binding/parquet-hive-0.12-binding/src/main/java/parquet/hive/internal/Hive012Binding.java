package parquet.hive.internal;


import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.PartitionDesc;
import org.apache.hadoop.hive.ql.plan.TableScanDesc;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.mapred.JobConf;

public class Hive012Binding extends AbstractHiveBinding {

  private MapWork mapWork;
  private Map<String, PartitionDesc> pathToPartitionInfo;

  /**
   * Initialize the mapWork variable in order to get all the partition and start to update the jobconf
   *
   * @param job
   */
  private void init(final JobConf job) {
    final String plan = HiveConf.getVar(job, HiveConf.ConfVars.PLAN);
    if (mapWork == null && plan != null && plan.length() > 0) {
      mapWork = Utilities.getMapWork(job);
      pathToPartitionInfo = new LinkedHashMap<String, PartitionDesc>();
      for (final Map.Entry<String, PartitionDesc> entry : mapWork.getPathToPartitionInfo().entrySet()) {
        pathToPartitionInfo.put(new Path(entry.getKey()).toUri().getPath().toString(), entry.getValue());
      }
    }
  }

  private PartitionDesc getPartition(final String path) {
    if (pathToPartitionInfo == null) {
      return null;
    }
    return pathToPartitionInfo.get(path);
  }

  private void pushProjectionsAndFilters(final JobConf jobConf,
      final String splitPath, final String splitPathWithNoSchema) {

    if (mapWork == null || mapWork.getPathToAliases() == null) {
      return;
    }

    final ArrayList<String> aliases = new ArrayList<String>();
    final Iterator<Entry<String, ArrayList<String>>> iterator = mapWork.getPathToAliases().entrySet().iterator();

    while (iterator.hasNext()) {
      final Entry<String, ArrayList<String>> entry = iterator.next();
      final String key = new Path(entry.getKey()).toUri().getPath();

      if (splitPath.equals(key) || splitPathWithNoSchema.equals(key)) {
        final ArrayList<String> list = entry.getValue();
        for (final String val : list) {
          aliases.add(val);
        }
      }
    }

    for (final String alias : aliases) {
      final Operator<? extends Serializable> op = mapWork.getAliasToWork().get(
              alias);
      if (op != null && op instanceof TableScanOperator) {
        final TableScanOperator tableScan = (TableScanOperator) op;

        // push down projections
        final ArrayList<Integer> list = tableScan.getNeededColumnIDs();

        if (list != null) {
          ColumnProjectionUtils.appendReadColumnIDs(jobConf, list);
        } else {
          ColumnProjectionUtils.setFullyReadColumns(jobConf);
        }

        pushFilters(jobConf, tableScan);
      }
    }
  }

  private void pushFilters(final JobConf jobConf, final TableScanOperator tableScan) {

    final TableScanDesc scanDesc = tableScan.getConf();
    if (scanDesc == null) {
      return;
    }

    // construct column name list for reference by filter push down
    Utilities.setColumnNameList(jobConf, tableScan);

    // push down filters
    final ExprNodeDesc filterExpr = scanDesc.getFilterExpr();
    if (filterExpr == null) {
      return;
    }

    final String filterText = filterExpr.getExprString();
    final String filterExprSerialized = Utilities.serializeExpression(filterExpr);
    jobConf.set(
            TableScanDesc.FILTER_TEXT_CONF_STR,
            filterText);
    jobConf.set(
            TableScanDesc.FILTER_EXPR_CONF_STR,
            filterExprSerialized);
  }

  
  @Override
  public JobConf pushProjectionsAndFilters(JobConf jobConf, Path path)
      throws IOException {
    init(jobConf);
    final JobConf cloneJobConf = new JobConf(jobConf);
    final PartitionDesc part = getPartition(path.toString());

    if ((part != null) && (part.getTableDesc() != null)) {
      Utilities.copyTableJobPropertiesToConf(part.getTableDesc(), cloneJobConf);
    }

    pushProjectionsAndFilters(cloneJobConf, path.toString(), path.toUri().toString());
    return cloneJobConf;
  }
}
