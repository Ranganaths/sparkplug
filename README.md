# ![Sparkplug](https://raw.githubusercontent.com/springnz/sparkplug/master/logos/sparkplug2.jpg "Sparkplug framework for functional Spark") Sparkplug 

**A framework for creating composable and pluggable data processing pipelines using [Apache Spark](http://spark.apache.org "Apache Spark"), and running them on a cluster.**

*Please note that this project is early stage work in progress, and will be subject to some breaking changes and refactoring.*

Apache Spark is great, but not everything you need to use it successfully in production environments comes out the box.

This project aims to bridge the gap. In particular, it addresses two specific requirements.

1. Creating data processing pipelines that are easy to reuse and test in isolation.
2. Providing a lightweight mechanism for launching and executing Spark processes on a cluster. 

These two requirements are quite different. Indeed it is possible to use Sparkplug for either of them without taking advantage of the other. For example it is possible to create composable data pipelines as described below, then execute them directly, or using any other Spark cluster execution or job manager of your choice.

## Data processing pipelines

The key abstraction here is the `SparkOperation` monad.

```scala
sealed trait SparkOperation[+A] {
  def run(ctx: SparkContext): A
}
```

`SparkOperation` are typically created using the companion class. Here is the simplest possible example:

```scala
val textRDDOperation = SparkOperation[RDD[String]] {
  ctx ⇒ ctx.makeRDD("There is nothing either good or bad, but thinking makes it so".split(' '))
}
```

This is a simple `SparkOperation` that takes a string and returns a `RDD[String]` consisting of the words of a sentence.

We can then use this `SparkOperation` to create another operation.

```scala
val letterCount: SparkOperation[Long] = for {
    logData ← textRDDProvider
  } yield logData.filter(_.contains("a")).count()
}
```

In this case we are counting the number of words that contain the letter 'a'.

Proceeding as in this simple example, we can create complex data processing pipelines, mainly using monadic operations. 

These include:

* Bread and butter map and flatmap to compose operations (as above).
* Combining operations (e.g. convert a tuple of `SparkOperation`s to a `SparkOperation` of tuples).
* Sequence operations (e.g. convert a list of `SparkOperation`s to a `SparkOperation` of list). 

Then once we have composed the `SparkOperation` as desired, it is against a given `SparkContext`.

```scala
val answer = letterCount.run(sparkContext)
```

The types of `SparkOperation`s are typically, at least until the final step of the pipeline, `RDD`s.

### Why go to all this trouble?

For simple processes, as above, it is overkill. However, non-trivial data processing pipelines typically involve many stages, and often there are many permutations over which these steps may be applied in different scenarios. 

Splitting the process into discrete, separate operations has two main advantages:

1. `SparkOperation`s, modular in nature, can easily be reused or shared across different data processing pipelines.
2. They can be unit tested in isolation. There are several utilities included in the project that facilitate this. This is covered in the section on testing below.
3. Operations can be glued together using compact functional code.

Note that this pattern involves decoupling the pipeline definition from the pipeline execution, which enables a great deal of flexibility over how one defines pipelines and executes them. 

It does lead to the one drawback in that stack dumps are not normally very meaningful. For this reason good logging and error handling is important.

### Wiring together SparkOperation components

A common use case requires creating a pipeline. The fist step in the pipeline is the data input step. Then there are operations that process this input data. 

As a simple example, consider a pipeline processing a corpus of documents:

1. The first step processes input data into a `RDD` of `Document`s. 
2. The next step is to transform `Document`s into `ParserInfo`s. 
3. The final step is to calculate document statistics, and return a `DocumentStats` object with summary statistics.

This can be represented by the following pipeline trait.

```scala
trait DocumentPipeline {
  def dataSource: SparkOperation[RDD[InputType]]

  lazy val createOperation: SparkOperation[RDD[Document]] = dataSource.map {
    input ⇒ createDocument(input)
  }
  
  lazy val parseOperation: SparkOperation[RDD[ParserInfo]] = createOperation.map {
    doc ⇒ parseDocument(doc)
  }
  
  lazy val statsOperation: SparkOperation[DocumentStats] = parseOperation.map {
    parsedDoc ⇒ calculateStats(parsedDoc)
  }
}
```

Note that `SparkOperation`s need not return `RDD`s, and in general, the final step in the pipeline will generally return something other than a `RDD`. 

This will generally be the final status after writing data to a database (`Try[Unit]` is good for this), or some summary/aggregate result.

The final result of the pipeline should be a serializable type.

### Testing

We wish to use a different data source for test and production environments. This can be done by applying the following overrides:

For the test environment:
```scala
trait TestDocPipeline extends DocumentPipeline {
  override lazy val dataSource = new TestRDDSource[InputType](fileName)
}
```
And for production:
```scala
trait ProdDocPipeline extends DocumentPipeline {
  override lazy val dataSource = new CassandraRDDSource[InputType](keySpace, table)
}
```

The `springnz.sparkplug.testkit` namespace contains methods to sample and persist `RDD`s at various stages in the pipeline. This enables isolated testing. It also relieves one from the burden of hand crafting test cases. 

## Execution on a cluster

The recommended mechanism for execution on a cluster (Java or Scala) is as follows:

1. Package your Java / Scala project into a assembly (a single jar with all the transitive dependencies, sometimes called an uber jar).
2. Invoke the `spark-submit` app, passing in the assembly into the command line. Your app is run on the cluster, and `spark-submit` terminates after your app finishes. The Spark project also contains a `SparkLauncher` class, which is a thin Scala wrapper around `spark-submit`.

However, there is still plenty of work to do to coordinate this in a production environment. If you are already doing this kind of work in Scala, the Spark Plug library is very useful.

Another issue is that creating assemblies can lead to all sorts of problems with conflicts in transitive dependencies, which are often difficult to resolve, especially if you don't even know what the these dependencies do. Assembblies can also get large really quickly, and can take a while for `spark-submit` to upload to the cluster.

A third issue is that ideally you want the cluster to be available when a job request arrives. However there is plenty that can be set up in advance in preparation, so that when the job request arrives, there is less that can go wrong. The `spark-submit` command line execution pattern doesn't easily facilitate that.

### How Sparkplug cluster execution works

The use case that Sparkplug cluster execution is particularly well suited to is where your overall technology stack is Scala based, and particular if Akka is a big part of it. If you have a polyglot stack, something like the REST based [Spark Job Server](https://github.com/spark-jobserver/spark-jobserver) may be more suitable.

Sparkplug launcher uses Akka remoting under the hood. Sparkplug launches jobs on the cluster using the following steps:

1. The client has an `ActorSystem` running, and an execution client actor.
2. This client invokes `spark-submit` to run an application on the server.
3. The server starts up it's own `ActorSystem`, and once this is done, sends a message to inform the client.
4. It creates a `SparkContext`, which is then available to service request to run Spark jobs that it may receive. The service is now ready for action. 
5. When a request arrives at the client, it sends a message to the server to process the request.
6. The job is then run by the server and the client is notified when it is done. The final result is streamed back to the client.

### Creating a SparkPlugin

It's really simple to create a Sparkplug plugin (hereafter referred as a SparkPlugin to avoid tautology.

Simply create a class that extends the `SparkPlugin` trait.
```scala
trait SparkPlugin {
  def apply(input: Any): SparkOperation[Any]
}
```
```scala
package mypackage

class DocumentStatsPlugin extends SparkPlugin {
  import ProdDocPipeline._

  override def apply(input: Any) = statsOperation
}
```

In this case the `DocumentStatsPlugin` is a hook to create a `statsOperation`, the a `SparkOperation` that calculate document statistics referred to above. In this case the input is not used. 

### Starting a job on the cluster

The recommended way to launch a Job on the cluster is via the futures interface.

```scala
trait ClientExecutor {
  def execute[A](pluginClass: String, data: Option[Any]): Future[A]
  def shutDown(): Unit
}
```

Use the `ClientExecutor` companion object to create an executor instance.
```scala
val executor = ClientExecutor.create()
```

Note that the executor can be called multiple times, but the `shutDown` method must be called to free up local and remote resources.

If you wish to execute low latency jobs, this is the way to go.

If you only intend invoking a single, long running job within a session, and don't care about the startup time, simply use the `apply` on the `ClientExecutor` object:
```scala
implicit val ec = scala.concurrent.ExecutionContext.global
val docStatsFuture = ClientExecutor[DocumentStats]("mypackage.DocumentStatsPlugin", None)
```

Executing a job on a Spark cluster doesn't get easier than this!

### Possible future interface changes

* A moderate refactoring of the client interface is planned to support running multiple server applications (each with their own SparkContext)
* It is also intended to provide a type safe plugin interface in the future - or at least investigate the possibility

## Projects

SparkPlug is set up as a sbt multi-project with the following subprojects:

* **sparkplug-core**: The core `SparkOperation` monad and related traits and interfaces.
* **sparkplug-extras**: Components for data access (currently Cassandra and SQL) and utilities for testing.
* **sparkplug-examples**: Several examples for how to create Spark pipelines. A good place to start.
* **sparkplug-executor**: The Server side of the cluster execution component.
* **sparkplug-launcher**: The Client side of the cluster execution component.

## Build and Test

There is a build dependency on [Spring NZ util-lib](https://github.com/springnz/util-lib). The easist is to clone the repository and run `sbt publish` (or `sbt publishLocal`) to publish to your (local) repository.

Then clone the repository and compile / test in sbt.

### Clustering

The cluster execution component has a few more moving parts:

As a bare minimum, Spark needs to be installed on the local machine. 

The standard Spark packages are all based on Scala 2.10. 
Sparkplug requires a Scala version 2.11 edition of Spark, which needs to be built from source.

See the [spark build documentation](https://spark.apache.org/docs/latest/building-spark.html) on how to do this.

**OR:**

Use the forked version of Spark: [https://github.com/springnz/spark](https://github.com/springnz/spark)

* Pull the branch corresponding to the [current Spark version.](https://raw.githubusercontent.com/springnz/sparkplug/master/project/Dependencies.scala)
* Execute the `make-sparkplug-distribution.sh` script to create a distribution.

When installing Spark, make sure the `SPARK_MASTER` and `SPARK_HOME` environment variables have been set.

*You can start a simple cluster on your workstation, or you can set `SPARK_MASTER="local[2]"` to run Spark in local mode (with 2 cores in this case).* 

All jars in your client application need to be in one folder. This can be done using [SBT Pack](https://github.com/xerial/sbt-pack).
Add the following line to your `plugins.sbt` file: `addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.7.5")` (see the SBT Pack docs for the latest info).

Then, before running the tests, run `sbt pack`. This copies all the .jar files to the folder `{projectdir}/target/pack/lib`. Any jars that are in this folder will be added to the submit request. 

### Environment variables

Environment variables may be required, particular for data connections:

#### Spark environment variables (for cluster execution)

* *SPARK_HOME*: Spark home folder
* *SPARK_MASTER*: Spark master location / URL
* *SPARK_EVENTLOG_ENABLED*: Used to turn on event logging for the Spark WebUI.
* *SPARK_EVENTLOG_DIR*: Location to log to for the Spark WebUI events.

#### Spark Remote Execution

* *SPARK_EXECUTOR_CORES*: Number of cores used per Spark executor (default 1).
* *SPARK_EXECUTOR_MEMORY*: Memory assigned to Spark executor (default 1g).
* *SPARK_DRIVER_MEMORY*: Memory assigned to Spark driver process (default 1g).

#### Cassandra Connections:

* *SPARK_CASSANDRA_CONNECTION_HOST*: Host name for Cassandra connections (defaults to 127.0.0.1)
* *SPARK_CASSANDRA_AUTH_USERNAME*: Username for authenticated cassandra connections
* *SPARK_CASSANDRA_AUTH_PASSWORD*: Password for authenticated cassandra connections

## TODO

There is plenty of work to do to get SparkPlug into fully fledged production mode.

This includes, but is not limited to:

* Support for more data sources.
* Better error handling and monitoring of cluster execution.
* Testing on Mesos and YARN clusters.
* (Possibly) extensions to support Spark Streaming.
* Creating an activator template for a Sparkplug client application.
* Plus many more...

Contributions are welcome.
