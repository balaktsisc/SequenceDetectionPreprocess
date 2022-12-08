package auth.datalab.siesta.S3Connector

import auth.datalab.siesta.BusinessLogic.DBConnector.DBConnector
import auth.datalab.siesta.BusinessLogic.ExtractSequence.ExtractSequence
import auth.datalab.siesta.BusinessLogic.ExtractSingle.ExtractSingle
import auth.datalab.siesta.BusinessLogic.Metadata.{MetaData, SetMetadata}
import auth.datalab.siesta.BusinessLogic.Model.Structs
import auth.datalab.siesta.CommandLineParser.Config
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel

import java.net.URI

class S3ConnectorTest extends DBConnector {

  var seq_table: String = _
  var meta_table: String = _
  var single_table: String = _
  var last_checked_table:String = _
  var index_table:String = _
  var count_table:String = _

  /**
   * Depending on the different database, each connector has to initialize the spark context
   */
  override def initialize_spark(config:Config): Unit = {
    lazy val spark = SparkSession.builder()
      .appName("Object Storage Test")
      .master("local[*]")
      .getOrCreate()

    //TODO: pass through environment vars
    val s3accessKeyAws = "minioadmin"
    val s3secretKeyAws = "minioadmin"
    val connectionTimeOut = "600000"
    val s3endPointLoc: String = "http://rabbit.csd.auth.gr:9000"

    spark.sparkContext.hadoopConfiguration.set("fs.s3a.endpoint", s3endPointLoc)
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.access.key", s3accessKeyAws)
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.secret.key", s3secretKeyAws)
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.connection.timeout", connectionTimeOut)
    //    spark.sparkContext.hadoopConfiguration.set("spark.sql.debug.maxToStringFields", "100")
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.path.style.access", "true")
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.connection.ssl.enabled", "true")
    spark.sparkContext.hadoopConfiguration.set("fs.s3a.bucket.create.enabled", "true")
    spark.conf.set("spark.sql.sources.partitionOverwriteMode", "dynamic")
    spark.conf.set("spark.sql.parquet.compression.codec",config.compression)
    spark.conf.set("spark.sql.parquet.filterPushdown","true")


  }

  /**
   * Create the appropriate tables, remove previous ones
   */
  override def initialize_db(config: Config): Unit = {
    val spark = SparkSession.builder().getOrCreate()
    val fs = FileSystem.get(new URI("s3a://siesta/"), spark.sparkContext.hadoopConfiguration)

    //define name tables
    seq_table = s"""s3a://siesta/${config.log_name}/seq.parquet/"""
    meta_table = s"""s3a://siesta/${config.log_name}/meta.parquet/"""
    single_table = s"""s3a://siesta/${config.log_name}/single.parquet/"""
    last_checked_table = s"""s3a://siesta/${config.log_name}/last_checked.parquet/"""
    index_table = s"""s3a://siesta/${config.log_name}/index.parquet/"""
    count_table = s"""s3a://siesta/${config.log_name}/count.parquet/"""

    //delete previous stored values
    if (config.delete_previous) fs.delete(new Path(s"""s3a://siesta/${config.log_name}/"""), true)

    //delete all stored indices in this db
    if (config.delete_all) fs.delete(new Path(s"""s3a://siesta/"""), true)


  }

  /**
   * This method constructs the appropriate metadata based on the already stored in the database and the
   * new presented in the config object
   *
   * @param config contains the configuration passed during execution
   * @return the metadata
   */
  override def get_metadata(config: Config): MetaData = {
    Logger.getLogger("Metadata").log(Level.INFO, s"Getting metadata")
    val start = System.currentTimeMillis()
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    //get previous values if exists
    val schema = ScalaReflection.schemaFor[MetaData].dataType.asInstanceOf[StructType]
    val metaDataObj = try {
      spark.read.schema(schema).json(meta_table)
    } catch {
      case _: org.apache.spark.sql.AnalysisException => null
    }
    val total = System.currentTimeMillis() - start
    Logger.getLogger("Metadata").log(Level.INFO, s"finished in ${total / 1000} seconds")
    //calculate new object
    val metaData = if (metaDataObj==null) {
      SetMetadata.initialize_metadata(config)
    }else{
      SetMetadata.load_metadata(metaDataObj)
    }
    this.write_metadata(metaData)//persist this version back
    metaData
  }

  override def write_metadata(metaData: MetaData): Unit = {
    metaData.has_previous_stored=true
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    val rdd = spark.sparkContext.parallelize(Seq(metaData))
    val df = rdd.toDF()
    df.write.mode(SaveMode.Overwrite).json(meta_table)
  }

  /**
   * Read data as an rdd from the SeqTable
   *
   * @param metaData Containing all the necessary information for the storing
   * @return In RDD the stored data
   */
  override def read_sequence_table(metaData: MetaData): RDD[Structs.Sequence] = {
    val spark = SparkSession.builder().getOrCreate()
    try{
      val df = spark.read.parquet(seq_table)
      S3Transformations.transformSeqToRDD(df)
    }catch {
      case _: org.apache.spark.sql.AnalysisException => null
    }
  }

  /**
   * This method writes traces to the auxiliary SeqTable. Since RDD will be used as intermediate results it is already persisted
   * and should not be modify that.
   * If states in the metadata, this method should combine the new traces with the previous ones
   * This method should combine the results with previous ones and return the results to the main pipeline
   * Additionally updates metaData object
   *
   * @param sequenceRDD RDD containing the traces
   * @param metaData    Containing all the necessary information for the storing
   */
  override def write_sequence_table(sequenceRDD: RDD[Structs.Sequence], metaData: MetaData): RDD[Structs.Sequence] = {
    Logger.getLogger("Sequence Table Write").log(Level.INFO,s"Start writing sequence table")
    val start = System.currentTimeMillis()
    val previousSequences = this.read_sequence_table(metaData) //get previous
    val combined = this.combine_sequence_table(sequenceRDD,previousSequences) //combine them
    val df = S3Transformations.transformSeqToDF(combined) //write them back
    metaData.traces = df.count()
    df.write.mode(SaveMode.Overwrite).parquet(seq_table)
    val total = System.currentTimeMillis() - start
    Logger.getLogger("Sequence Table Write").log(Level.INFO,s"finished in ${total/1000} seconds")
    combined
  }

  override def combine_sequence_table(newSequences: RDD[Structs.Sequence], previousSequences: RDD[Structs.Sequence]): RDD[Structs.Sequence] = {
    if(previousSequences==null) return newSequences
    val combined = previousSequences.keyBy(_.sequence_id)
      .fullOuterJoin(previousSequences.keyBy(_.sequence_id))
      .map(x=>{
        val prevEvents = x._2._1.getOrElse(Structs.Sequence(List(),-1)).events
        val newEvents = x._2._2.getOrElse(Structs.Sequence(List(),-1)).events
        Structs.Sequence(ExtractSequence.combineSequences(prevEvents,newEvents),x._1)
      })
    combined
  }


  /**
   * This method writes traces to the auxiliary SingleTable. The rdd that comes to this method is not persisted.
   * Database should persist it before store it and not persist it at the end.
   * This method should combine the results with previous ones and return the results to the main pipeline
   * Additionally updates metaData object
   *
   * @param singleRDD Contains the single inverted index
   * @param metaData  Containing all the necessary information for the storing
   */
  override def write_single_table(singleRDD: RDD[Structs.InvertedSingleFull], metaData: MetaData): RDD[Structs.InvertedSingleFull] = {
    Logger.getLogger("Single Table Write").log(Level.INFO, s"Start writing single table")
    val start = System.currentTimeMillis()
    val newEvents = singleRDD.map(x=>x.times.size).reduce((x,y)=>x+y)
    val previousSingle = read_single_table(metaData)
    val combined = combine_single_table(singleRDD,previousSingle)
    val df = S3Transformations.transformSingleToDF(combined)//transform
    metaData.events+=newEvents//count and update metadata
    df.persist(StorageLevel.MEMORY_AND_DISK)
    df
      .repartition(col("event_type"))
      .write.partitionBy("event_type")
      .mode(SaveMode.Overwrite).parquet(single_table) //store to s3
    val total = System.currentTimeMillis() - start
    df.unpersist()
    Logger.getLogger("Single Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
    combined
  }

  /**
   * Read data as an rdd from the SingleTable
   *
   * @param metaData Containing all the necessary information for the storing
   * @return In RDD the stored data
   */
  override def read_single_table(metaData: MetaData): RDD[Structs.InvertedSingleFull] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      val df = spark.read.parquet(single_table)
      S3Transformations.transformSingleToRDD(df)
    } catch {
      case _: org.apache.spark.sql.AnalysisException => null
    }
  }


  override def combine_single_table(newSingle: RDD[Structs.InvertedSingleFull], previousSingle: RDD[Structs.InvertedSingleFull]): RDD[Structs.InvertedSingleFull] = {
    if (previousSingle == null) return newSingle
    val combined = previousSingle.keyBy(x=>(x.id,x.event_name))
      .rightOuterJoin(newSingle.keyBy(x=>(x.id,x.event_name)))
      .map(x=>{
        val previous = x._2._1.getOrElse(Structs.InvertedSingleFull(-1,"",List(),List()))
        val prevOc = previous.times.zip(previous.positions)
        val newOc = x._2._2.times.zip(x._2._2.positions)
        val combine = ExtractSingle.combineTimes(prevOc,newOc)
        Structs.InvertedSingleFull(x._1._1,x._1._2,combine.map(_._1),combine.map(_._2))
      })
    combined
  }

  override def read_last_checked_table(metaData: MetaData): RDD[Structs.LastChecked] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      val df = spark.read.parquet(last_checked_table)
      S3Transformations.transformLastCheckedToRDD(df)
    } catch {
      case _: org.apache.spark.sql.AnalysisException => null
    }
  }

  override def write_last_checked_table(lastChecked: RDD[Structs.LastChecked], metaData: MetaData): RDD[Structs.LastChecked] = {
    Logger.getLogger("LastChecked Table Write").log(Level.INFO, s"Start writing LastChecked table")
    val start = System.currentTimeMillis()
    val previousLastChecked = this.read_last_checked_table(metaData)
    val combined = this.combine_last_checked_table(lastChecked,previousLastChecked)
    val df = S3Transformations.transformLastCheckedToDF(combined)
    df.repartition(col("eventA"))
      .write.partitionBy("eventA")
      .mode(SaveMode.Overwrite).parquet(last_checked_table)
    val total = System.currentTimeMillis() - start
    Logger.getLogger("LastChecked Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
    combined
  }

  override def combine_last_checked_table(newLastChecked: RDD[Structs.LastChecked], previousLastChecked: RDD[Structs.LastChecked]): RDD[Structs.LastChecked] = {
    if (previousLastChecked == null) return newLastChecked
    val combined = previousLastChecked.keyBy(x=>(x.id,x.eventA,x.eventB))
      .fullOuterJoin(newLastChecked.keyBy(x=>(x.id,x.eventA,x.eventB)))
      .map(x=>{
        val prevLC = x._2._1.getOrElse(Structs.LastChecked("","",-1,""))
        val newLC = x._2._2.getOrElse(Structs.LastChecked("","",-1,""))
        val time = if(newLC.timestamp=="") prevLC.timestamp else newLC.timestamp
        val events = if(newLC.eventA=="") (prevLC.eventA,prevLC.eventB) else (newLC.eventA,newLC.eventB)
        val id = if(newLC.id == -1) prevLC.id else newLC.id
        Structs.LastChecked(events._1,events._2,id,time)
      })
    combined

  }

  override def read_index_table(metaData: MetaData, intervals: List[Structs.Interval]): RDD[Structs.PairFull] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      val parqDF = spark.read.parquet(this.index_table)
      parqDF.createOrReplaceTempView("IndexTable")
      val interval_min = intervals.map(_.start).distinct.sortWith((x,y)=>x.before(y)).head
      val interval_max = intervals.map(_.end).distinct.sortWith((x,y)=>x.before(y)).head
      val parkSQL = spark.sql(s"""select * from IndexTable where (start>=to_timestamp('$interval_min') and end<=to_timestamp('$interval_max'))""")
      S3Transformations.transformIndexToRDD(parkSQL,metaData)
    } catch {
      case _: org.apache.spark.sql.AnalysisException => null
    }
  }

  override def combine_index_table(newPairs: RDD[Structs.PairFull], prevPairs: RDD[Structs.PairFull], metaData: MetaData, intervals: List[Structs.Interval]): RDD[Structs.PairFull] = {
    if (prevPairs == null) return newPairs
    newPairs.union(prevPairs)
  }

  override def write_index_table(newPairs: RDD[Structs.PairFull], metaData: MetaData, intervals: List[Structs.Interval]): Unit = {
    Logger.getLogger("Index Table Write").log(Level.INFO, s"Start writing Index table")
    val start = System.currentTimeMillis()
    val previousIndexed = this.read_index_table(metaData, intervals)
    val combined = this.combine_index_table(newPairs,previousIndexed,metaData, intervals)
    metaData.pairs+=combined.count()
    val df = S3Transformations.transformIndexToDF(combined,metaData)
    df.repartition(col("interval"))
      .select("interval.start","interval.end","eventA","eventB","occurrences")
          .write.partitionBy("start","end","eventA")
      .mode(SaveMode.Overwrite).parquet(this.index_table)
    val total = System.currentTimeMillis() - start
    Logger.getLogger("Index Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")

  }

  override def read_count_table(metaData: MetaData): RDD[Structs.Count] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      val df = spark.read.parquet(this.count_table)
      S3Transformations.transformCountToRDD(df)
    } catch {
      case _: org.apache.spark.sql.AnalysisException => null
    }

  }

  override def write_count_table(counts: RDD[Structs.Count], metaData: MetaData): Unit = {
    Logger.getLogger("Count Table Write").log(Level.INFO, s" writing Count table")
    val start = System.currentTimeMillis()
    val previousIndexed = this.read_count_table(metaData)
    val combined = this.combine_count_table(counts,previousIndexed,metaData)
    val df = S3Transformations.transformCountToDF(combined)
    df.repartition(col("eventA"))
      .write.partitionBy( "eventA")
      .mode(SaveMode.Overwrite).parquet(this.count_table)
    val total = System.currentTimeMillis() - start
    Logger.getLogger("Count Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")

  }


}
