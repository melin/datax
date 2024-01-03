package com.superior.datatunnel.examples.jdbc

import com.superior.datatunnel.core.DataTunnelExtensions
import org.apache.spark.sql.SparkSession

object DataTunnelMysql2LogDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("HADOOP_USER_NAME", "hdfs");

        val spark = SparkSession
            .builder()
            .master("local")
            .enableHiveSupport()
            .appName("Datatunnel spark example")
            .config("spark.sql.extensions", DataTunnelExtensions::class.java.name)
            .getOrCreate()

        val sql = """
            DATATUNNEL SOURCE("mysql") OPTIONS (
              username = "root",
              password = "root2023",
              host = '172.18.5.44',
              port = 3306,
              schemaName = 'cdc_demos_[0-9]+',
              tableName = 'account_[0-9]+',
              columns = ["id", "name", "dt_meta_table"],
              condition = "where 1=1 limit 1"
            ) 
            SINK("log") OPTIONS (
              columns = ["id", "username", "table_name"]
            )
        """.trimIndent()

        spark.sql(sql)
    }
}