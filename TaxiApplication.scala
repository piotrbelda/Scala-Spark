package BigDataProject

import org.apache.spark.sql.{Column, SparkSession}
import org.apache.spark.sql.functions._

object TaxiApplication extends App {

  val spark = SparkSession.builder().config("spark.master","local")
    .appName("Taxi Big Data Application").getOrCreate()

  import spark.implicits._

  val taxiDF = spark.read.load("D:\\spark-essentials-master\\src\\main\\resources\\data\\yellow_taxi_jan_25_2018")
  taxiDF.printSchema()
  println(taxiDF.count())

  /* Reading in 32GB parquet file
  val bigTaxiDF = spark.read.load("D:\\NYC_taxi_2009-2016.parquet")
  println(bigTaxiDF.count())
   */

  val taxiZonesDF = spark.read.option("header","true")
    .option("inferSchema","true").csv("src/main/resources/data/taxi_zones.csv")

  taxiZonesDF.printSchema()

  /*
  1. Which zones have the most pickups/dropoffs overall?
   */

  val pickupsByTaxiZoneDF = taxiDF.groupBy("PULocationID").agg(count("*").as("totalTrips"))
    .join(taxiZonesDF, col("PULocationID") === col("LocationID"))
    .drop("LocationID","service_zone").orderBy(col("totalTrips").desc_nulls_last)

  // Grouping data by borough column

  val pickupsByBorough = pickupsByTaxiZoneDF.groupBy("Borough").agg(sum(col("totalTrips"))
  .as("totalTrips")).orderBy(col("totalTrips").desc_nulls_last)

  /*
  2. What are the peak hours for taxi?
   */

  val pickupsByHourDF = taxiDF
    .withColumn("hour_of_day",hour(col("tpep_pickup_datetime")))
    .groupBy("hour_of_day")
    .agg(count("*").as("totalTrips"))
    .orderBy(col("totalTrips").desc_nulls_last)

  /*
  3. How are the trips distributed by length? Why are people taking the cab?
   */

  val tripDistanceDF = taxiDF.select(col("trip_distance").as("distance"))
  val longDistanceThreshold = 30 // in miles
  val tripDistanceStatsDF = tripDistanceDF.select(
    count("*").as("count"),
    lit(longDistanceThreshold).as("threshold"),
    mean("distance").as("mean"),
    stddev("distance").as("stddev"),
    min("distance").as("min"),
    max("distance").as("max")
  )

  tripDistanceStatsDF.show()

  val tripsWithLengthDF = taxiDF
    .withColumn("isLong",col("trip_distance") >= longDistanceThreshold)

  val tripsByLengthDF = tripsWithLengthDF.groupBy("isLong").count()
  tripsByLengthDF.show()

  /*
  4. What are the peak hours for long/short trips?
   */

  val pickupsByHourByLengthDF = tripsWithLengthDF
    .withColumn("hour_of_day",hour(col("tpep_pickup_datetime")))
    .groupBy("hour_of_day","isLong")
    .agg(count("*").as("totalTrips"))
    .orderBy(col("totalTrips").desc_nulls_last)

  /*
  5. What are the top 3 pickup/dropoff zones for long/short trips?
   */
  def pickupDropoffPopularity(predicate: Column) = tripsWithLengthDF.where(predicate)
    .groupBy("PULocationID","DOLocationID").agg(count("*").as("totalTrips"))
    .join(taxiZonesDF,col("PULocationID") === col("LocationID"))
    .withColumnRenamed("Zone","Pickup_Zone")
    .drop("LocationID","Borough","service_zone")
    .join(taxiZonesDF,col("DOLocationID") === col("LocationID"))
    .withColumnRenamed("Zone","Dropoff_Zone")
    .drop("LocationID","Borough","service_zone")
    .drop("PULocationID","DOLocationID")
    .orderBy(col("totalTrips").desc_nulls_last)

  pickupDropoffPopularity(col("isLong")).show()
  pickupDropoffPopularity(not(col("isLong"))).show()

  /*
  6. How are people paying for the ride, on long/short trips?
   */

  val ratecodeDistributionDF = taxiDF.groupBy(col("RateCodeID"))
    .agg(count("*").as("totalTrips"))
    .orderBy(col("totalTrips").desc_nulls_last)

  /*
  7. How is the payment type evolving with time?
   */

  val ratecodeEvolution = taxiDF
    .groupBy(to_date(col("tpep_pickup_datetime")).as("pickup_day"),col("RatecodeID"))
    .agg(count("*").as("totalTrips")).orderBy(col("pickup_day"))

  /*
  8. Can we explore a ride-sharing opportunity by grouping close short trips?
   */

  val groupAttemptsDF = taxiDF.select(round(unix_timestamp(col("tpep_pickup_datetime")) / 300)
    .cast("integer").as("fiveMinId"),col("PULocationID"),col("total_amount"))
    .where(col("passenger_count")<3)
    .groupBy(col("fiveMinId"),col("PULocationID"))
    .agg(count("*").as("totalTrips"),sum(col("total_amount")).as("total_amount"))
    .orderBy(col("totalTrips").desc_nulls_last)
    .withColumn("approximate_datetime",from_unixtime(col("fiveMinId") * 300))
    .drop("fiveMinId")
    .join(taxiZonesDF,col("PULocationID") === col("LocationID"))
    .drop("LocationID","service_zone")

  val percentGroupAttempt = 0.05
  val percentAcceptGrouping = 0.3
  val discount = 5
  val extraCost = 2
  val avgCostReduction = 0.6 * taxiDF.select(avg(col("total_amount"))).as[Double].take(1)(0)

  val groupingEstimateEconomicImpactDF = groupAttemptsDF
    .withColumn("groupedRides",col("totalTrips") * percentGroupAttempt)
    .withColumn("acceptedGroupRidesEconomicImpact",
      col("groupedRides") * percentAcceptGrouping * (avgCostReduction - discount))
    .withColumn("rejectedGroupRidesEconomicImpact",
      col("groupedRides") * (1 - percentAcceptGrouping) * extraCost)
    .withColumn("totalImpact",col("acceptedGroupRidesEconomicImpact") + col("rejectedGroupRidesEconomicImpact"))

  groupingEstimateEconomicImpactDF.show()

  val totalProfitDF = groupingEstimateEconomicImpactDF.select(sum(col("totalImpact")).as("total"))
  totalProfitDF.show()

}
