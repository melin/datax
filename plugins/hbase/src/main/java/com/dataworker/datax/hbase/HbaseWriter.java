package com.dataworker.datax.hbase;

import com.alibaba.fastjson.JSON;
import com.dataworker.datax.api.DataXException;
import com.dataworker.datax.api.DataxWriter;
import com.dataworker.datax.hbase.constant.MappingMode;
import com.dataworker.datax.hbase.constant.WriteMode;
import com.dataworker.datax.hbase.util.DistCpUtil;
import com.dataworker.spark.jobserver.api.LogUtils;
import com.dazhenyun.hbasesparkproto.HbaseBulkLoadTool;
import com.dazhenyun.hbasesparkproto.function.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.spark.HbaseTableMeta;
import org.apache.hadoop.hbase.spark.JavaHBaseContext;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

import static com.dataworker.datax.hbase.constant.HbaseWriterOption.*;

/**
 * @author melin 2021/7/27 11:06 上午
 */
public class HbaseWriter implements DataxWriter {

    private static final Logger logger = LoggerFactory.getLogger(HbaseWriter.class);

    /**
     * mappingMode模式下,合并字段的默认列名
     */
    public static final String DEFAULT_MERGE_QUALIFIER = "merge";

    /**
     * 默认hfile的最大大小
     */
    public static final long DEFAULT_HFILE_MAX_SIZE = HConstants.DEFAULT_MAX_FILE_SIZE * 5;

    @Override
    public void validateOptions(Map<String, String> options) {
        logger.debug("HbaseWriter options={}", JSON.toJSONString(options));

        if (StringUtils.isBlank(options.get(TABLE))){
            throw new DataXException("缺少table参数");
        }

        if (StringUtils.isBlank(options.get(HFILE_DIR))){
            throw new DataXException("缺少hfileDir参数");
        }

        if (Objects.isNull(EnumUtils.getEnum(WriteMode.class, options.get(WRITE_MODE)))){
            throw new DataXException("writeMode参数错误,支持bulkLoad或thinBulkLoad");
        }

        if (Objects.isNull(EnumUtils.getEnum(MappingMode.class, options.get(MAPPING_MODE)))){
            throw new DataXException("mappingMode参数错误,支持one2one或arrayZstd");
        }

        if (Objects.isNull(BooleanUtils.toBooleanObject(options.get(DO_BULKLOAD)))){
            throw new DataXException("doBulkLoad参数错误,支持true或false");
        }

        if (!Objects.isNull(options.get(HFILE_MAX_SIZE))){
            if (NumberUtils.toLong(options.get(HFILE_MAX_SIZE)) <= 0){
                throw new DataXException("hfileMaxSize参数错误");
            }
        }

        if (!Objects.isNull(options.get(HFILE_TIME))){
            if (!NumberUtils.isCreatable(HFILE_TIME)){
                throw new DataXException("hfileTime参数错误");
            }
        }

        if (BooleanUtils.toBooleanObject(options.get(DO_BULKLOAD))){
            if (NumberUtils.toInt(options.get(DISTCP_MAXMAPS)) <= 0){
                throw new DataXException("distcp.maxMaps参数未配置或配置错误");
            }
            if (NumberUtils.toInt(options.get(DISTCP_MAPBANDWIDTH)) <= 0){
                throw new DataXException("distcp.mapBandwidth参数未配置或配置错误");
            }
        }
    }

    @Override
    public void write(SparkSession sparkSession, Dataset<Row> dataset, Map<String, String> options) throws IOException {
        //实例编号
        String jobInstanceCode = sparkSession.sparkContext().getConf().get("spark.datawork.job.code");
        if (StringUtils.isBlank(jobInstanceCode)){
            throw new DataXException("实例编号为空");
        }

        Configuration config = null;
        //初始化和认证
        try {
            config = initAndKerberos(sparkSession, "source");
        } catch (Exception e) {
            logger.error("初始化集群配置和认证失败", e);
            LogUtils.error(sparkSession, "初始化集群配置和认证失败");
            throw new DataXException("初始化集群配置和认证失败", e);
        }

        logger.info("jobInstanceCode={} source 认证成功", jobInstanceCode);

        //hfile路径
        String hfileDir = options.get(HFILE_DIR);
        String tmpDir = buildTmpDir(hfileDir, jobInstanceCode);
        String stagingDir = HbaseBulkLoadTool.buildStagingDir(tmpDir);
        logger.debug("hfile路径:{}", stagingDir);
        LogUtils.info(sparkSession, "hfile路径:" + stagingDir);

        //hfile生成成功后的路径
        String stagingDirSucc = buildStagingDirSucc(stagingDir);
        Path stagingDirPath = new Path(stagingDir);
        Path stagingDirSuccPath = new Path(stagingDirSucc);

        //判断目录是否存在
        FileSystem fileSystem = FileSystem.get(config);
        try {
            if (fileSystem.exists(stagingDirPath)){
                fileSystem.delete(stagingDirPath, true);
                logger.warn("目录{}已存在,清空目录", stagingDir);
                LogUtils.warn(sparkSession, "目录" + stagingDir + "已存在,清空目录");
            }
            if (fileSystem.exists(stagingDirSuccPath)){
                fileSystem.delete(stagingDirSuccPath, true);
                logger.warn("目录{}已存在,清空目录", stagingDirSucc);
                LogUtils.warn(sparkSession, "目录" + stagingDirSucc + "已存在,清空目录");
            }
        } catch (Exception e) {
            logger.error("jobInstanceCode={}清空目录异常", jobInstanceCode, e);
            LogUtils.error(sparkSession, "jobInstanceCode=" + jobInstanceCode + "清空目录异常");
            throw new DataXException("jobInstanceCode=" + jobInstanceCode + "清空目录异常", e);
        }

        //生成hfile
        logger.debug("jobInstanceCode={} 开始生成hfile", jobInstanceCode);
        WriteMode writeMode = EnumUtils.getEnum(WriteMode.class, options.get(WRITE_MODE));
        MappingMode mappingMode = EnumUtils.getEnum(MappingMode.class, options.get(MAPPING_MODE));
        String table = options.get(TABLE);

        Connection connection = ConnectionFactory.createConnection(config);

        HbaseTableMeta hbaseTableMeta = null;
        try {
            hbaseTableMeta = HbaseBulkLoadTool.buildHbaseTableMeta(connection, table, tmpDir);
        } catch (Exception e) {
            logger.error("jobInstanceCode={}创建hbaseTableMeta失败", jobInstanceCode, e);
            LogUtils.error(sparkSession, "jobInstanceCode=" + jobInstanceCode + "创建hbaseTableMeta失败" + e.getMessage());
            throw new DataXException("jobInstanceCode=" + jobInstanceCode + "创建hbaseTableMeta失败", e);
        } finally {
            if (!connection.isClosed()){
                connection.close();
            }
        }
        int regionSize = hbaseTableMeta.getStartKeys().length;
        logger.info("table={} regionSize={}", table, regionSize);
        LogUtils.info(sparkSession, "table=" + table + " regionSize=" + regionSize);

        JavaSparkContext jsc = new JavaSparkContext(sparkSession.sparkContext());
        JavaHBaseContext javaHBaseContext = new JavaHBaseContext(jsc, config);

        try {
            if (WriteMode.bulkLoad.equals(writeMode)){
                BulkLoadFunctional functional = null;
                if (MappingMode.one2one.equals(mappingMode)){
                    functional = new One2OneBulkLoadFunction(hbaseTableMeta.getColumnFamily());
                } else {
                    functional = new ArrayZstdBulkLoadFunction(hbaseTableMeta.getColumnFamily(), Bytes.toBytes(options.getOrDefault(MERGE_QUALIFIER, DEFAULT_MERGE_QUALIFIER)));
                }

                HbaseBulkLoadTool.dazhenBulkLoad(javaHBaseContext,
                        dataset,
                        hbaseTableMeta,
                        functional,
                        BooleanUtils.toBoolean(options.get(COMPACTION_EXCLUDE)),
                        Optional.ofNullable(options.get(HFILE_MAX_SIZE)).map((size)->NumberUtils.toLong(size)).orElse(DEFAULT_HFILE_MAX_SIZE),
                        Optional.ofNullable(options.get(HFILE_TIME)).map((time)->NumberUtils.toLong(time)).orElse(System.currentTimeMillis()),
                        options
                        );

            } else {
                ThinBulkLoadFunctional functional = null;
                if (MappingMode.one2one.equals(mappingMode)){
                    functional = new One2OneThinBulkLoadFunction(hbaseTableMeta.getColumnFamily());
                } else {
                    functional = new ArrayZstdThinBulkLoadFunction(hbaseTableMeta.getColumnFamily(), Bytes.toBytes(options.getOrDefault(MERGE_QUALIFIER, DEFAULT_MERGE_QUALIFIER)));
                }
                HbaseBulkLoadTool.dazhenBulkLoadThinRows(javaHBaseContext,
                        dataset,
                        hbaseTableMeta,
                        functional,
                        BooleanUtils.toBoolean(options.get(COMPACTION_EXCLUDE)),
                        Optional.ofNullable(options.get(HFILE_MAX_SIZE)).map((size)->NumberUtils.toLong(size)).orElse(DEFAULT_HFILE_MAX_SIZE),
                        Optional.ofNullable(options.get(HFILE_TIME)).map((time)->NumberUtils.toLong(time)).orElse(System.currentTimeMillis()),
                        options
                );
            }
        } catch (Exception e) {
            logger.error("jobInstanceCode={} hfile生成失败", jobInstanceCode, e);
            LogUtils.error(sparkSession, "jobInstanceCode=" + jobInstanceCode + " hfile生成失败");
            //清理目录
            fileSystem.delete(stagingDirPath, true);
            throw new DataXException("jobInstanceCode=" + jobInstanceCode + " hfile生成失败");
        }
        logger.info("jobInstanceCode={} hfile生成成功", jobInstanceCode);
        LogUtils.info(sparkSession, "jobInstanceCode=" + jobInstanceCode + " hfile生成成功");

        //目录改成.succ后缀
        fileSystem.rename(stagingDirPath, stagingDirSuccPath);
        logger.debug("jobInstanceCode={} rename成功, path={},", jobInstanceCode, stagingDirSuccPath);

        try {
            //统计
            statisticsHfileInfo(sparkSession, config, stagingDirSuccPath, jobInstanceCode);
        } catch (Exception e) {
            logger.error("jobInstanceCode={} 统计hfile信息异常", jobInstanceCode, e);
            LogUtils.error(sparkSession, "jobInstanceCode=" + jobInstanceCode + "统计hfile信息异常");
        }

        //是否进行bulkload
        if (BooleanUtils.toBooleanObject(options.get(DO_BULKLOAD))){
            try {
                Configuration destConfig = initAndKerberos(sparkSession, "dest");
                int maxMaps = Integer.valueOf(options.get(DISTCP_MAXMAPS));
                int mapBandwidth = Integer.valueOf(options.get(DISTCP_MAPBANDWIDTH));

                String distHfileDir = options.get(DISTCP_HFILE_DIR);

                String distTmpDir = null;
                if (StringUtils.isBlank(distHfileDir)){
                    distTmpDir = tmpDir;
                } else {
                    distTmpDir = buildTmpDir(distHfileDir, jobInstanceCode);
                }
                String distStagingDir = HbaseBulkLoadTool.buildStagingDir(distTmpDir);

                DistCpUtil.distcp(config,
                        Arrays.asList(fileSystem.getFileStatus(stagingDirSuccPath).getPath()),
                        new Path(destConfig.get("fs.defaultFS") + distStagingDir),
                        maxMaps,
                        mapBandwidth
                        );

                //distcp成功后创建distcp.succ文件
                FileSystem destFileSystem = FileSystem.get(destConfig);
                destFileSystem.create(new Path(distStagingDir, "distcp.succ"));
                logger.info("jobInstanceCode={} distcp成功", jobInstanceCode);
                LogUtils.info(sparkSession, "jobInstanceCod=" + jobInstanceCode + "distcp成功");
                HbaseBulkLoadTool.loadIncrementalHFiles(ConnectionFactory.createConnection(destConfig), table, distTmpDir);

                //删除原集群数据
                fileSystem.delete(stagingDirSuccPath, true);
            } catch (Exception e) {
                logger.error("jobInstanceCode={} distcp失败", jobInstanceCode, e);
                LogUtils.error(sparkSession, "jobInstanceCode=" + jobInstanceCode + "distcp失败");
                throw new DataXException("jobInstanceCode=" + jobInstanceCode + "distcp失败");

            }
        }
        logger.info("jobInstanceCode={} hbaseWriter成功", jobInstanceCode);
        LogUtils.info(sparkSession, "jobInstanceCod=" + jobInstanceCode + "hbaseWriter成功");
    }

    public static Configuration initAndKerberos(SparkSession sparkSession, String env) throws Exception{
        //kerberos 认证
        Properties properties = new Properties();
        properties.load(Thread.currentThread().getContextClassLoader().getResourceAsStream(env + "/config.properties"));
        String princ = properties.getProperty("kerberos.princ");
        String keytabPath = Thread.currentThread().getContextClassLoader().getResource(env + "/kerberos-admin.keytab").getPath();
        String krb5Path = Thread.currentThread().getContextClassLoader().getResource(env + "/krb5.conf").getPath();
        System.setProperty("java.security.krb5.conf", krb5Path);
        System.setProperty("sun.security.krb5.debug", "false");

        //集群参数
        Configuration config = new Configuration(false);
        config.addResource(sparkSession.sparkContext().hadoopConfiguration());
        config.addResource(Thread.currentThread().getContextClassLoader().getResource(env + "/core-site.xml"));
        config.addResource(Thread.currentThread().getContextClassLoader().getResource(env + "/hbase-site.xml"));
        config.addResource(Thread.currentThread().getContextClassLoader().getResource(env + "/hdfs-site.xml"));
        config.addResource(Thread.currentThread().getContextClassLoader().getResource(env + "/yarn-site.xml"));
        //Kerberos 认证
        UserGroupInformation.setConfiguration(config);
        UserGroupInformation.loginUserFromKeytab(princ, keytabPath);
        return config;
    }

    private String buildTmpDir(String stagingDir, String jobInstanceCode){
        StringBuilder sb = new StringBuilder();
        sb.append(LocalDate.now());
        if (stagingDir.startsWith("/")){
            sb.append(stagingDir);
        } else {
            sb.append("/").append(stagingDir);
        }
        if (!stagingDir.endsWith("/")){
            sb.append("/");
        }
        sb.append(jobInstanceCode);
        return sb.toString();
    }

    private String buildStagingDirSucc(String stagingDir){
        return stagingDir + "_succ";
    }

    /**
     * 统计hfile信息
     * @param sparkSession
     * @param config
     * @param path
     * @param jobInstanceCode
     * @throws IOException
     */
    private void statisticsHfileInfo(SparkSession sparkSession, Configuration config, Path path, String jobInstanceCode) throws Exception{
        StringBuilder sb = new StringBuilder(" hfile生成成功, 临时路径：" + path + ":\n");
        long totalSize = 0;
        FileSystem fileSystem = FileSystem.get(config);
        FileStatus[] parentFileStatus = fileSystem.listStatus(path);
        for (FileStatus parent : parentFileStatus){
            FileStatus[] childFileStatus = fileSystem.listStatus(parent.getPath());
            for (int i = 0; i < childFileStatus.length; i++){
                sb.append("hfile_" + i + ":" + FileUtils.byteCountToDisplaySize(childFileStatus[i].getLen()) + "\n");
                totalSize += childFileStatus[i].getLen();
            }
        }
        sb.append("hfile总大小:" + FileUtils.byteCountToDisplaySize(totalSize));
        logger.info("jobInstanceCode={}" + sb.toString(), jobInstanceCode);
        LogUtils.info(sparkSession, "jobInstanceCode" + jobInstanceCode + sb.toString());
    }
}
