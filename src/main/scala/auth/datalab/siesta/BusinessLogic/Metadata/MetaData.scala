package auth.datalab.siesta.BusinessLogic.Metadata

case class MetaData(var traces:Long, var events:Long, indexed_tuples:Int, var pairs:Long,
               lookback: Int, split_every_days:Int,
               last_interval: String, var has_previous_stored: Boolean,
              filename:String, log_name: String, mode:String, compression:String) extends Serializable {



}
