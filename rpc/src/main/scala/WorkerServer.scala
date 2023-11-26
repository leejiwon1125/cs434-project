package kr.ac.postech.paranode.rpc

import com.google.protobuf.ByteString
import io.grpc.Server
import io.grpc.ServerBuilder
import kr.ac.postech.paranode.core._

import java.util.logging.Logger
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.reflect.io.Path
import worker._

import org.apache.logging.log4j.scala.Logging

class WorkerServer(
    executionContext: ExecutionContext,
    port: Int,
    inputDirectories: Array[Path],
    outputDirectory: Path
) extends Logging { self =>
  private[this] val server: Server = ServerBuilder
    .forPort(port)
    .addService(WorkerGrpc.bindService(new WorkerImpl, executionContext))
    .build()

  def start(): Unit = {
    server.start()

    logger.debug(
      s"WorkerServer listening on port $port with inputDirectories: ${inputDirectories
          .mkString(", ")} and outputDirectory: $outputDirectory"
    )

    sys.addShutdownHook {
      logger.error("*** shutting down gRPC server since JVM is shutting down")
      self.stop()
      logger.error("*** server shut down")
    }
  }

  def stop(): Unit = {
    if (server != null) {
      server.shutdown()
    }
  }

  def blockUntilShutdown(): Unit = {
    if (server != null) {
      server.awaitTermination()
    }
  }

  private class WorkerImpl extends WorkerGrpc.Worker {
    override def sample(request: SampleRequest): Future[SampleReply] = {
      val promise = Promise[SampleReply]

      Future {
        logger.debug(s"Sample request: $request")

        try {
          val sampledKeys = inputDirectories
            .map(_.toDirectory)
            .flatMap(_.list)
            .map(f => Block.fromPath(f.path))
            .flatMap(_.sample(request.numberOfKeys))
            .map(key => ByteString.copyFrom(key.underlying))

          promise.success(SampleReply(sampledKeys))
        } catch {
          case e: Exception =>
            println(e)
            promise.failure(e)
        }
      }(executionContext)

      promise.future
    }

    override def partition(
        request: PartitionRequest
    ): Future[PartitionReply] = {
      val promise = Promise[PartitionReply]

      Future {
        try {
          val block = Block.fromPath(Path("data/block"), 10, 90)
          request.workers
            .map(workerMetadata => {
              val keyRange = KeyRange(
                Key.fromString(workerMetadata.keyRange.get.from.toStringUtf8),
                Key.fromString(workerMetadata.keyRange.get.to.toStringUtf8)
              )
              val partition = block.partition(keyRange)
              val partitionPath = Path(
                s"data/partition/${workerMetadata.node.get.host}:${workerMetadata.node.get.port}"
              )
              partition._2.writeTo(partitionPath)
            })

          promise.success(new PartitionReply())
        } catch {
          case e: Exception =>
            println(e)
            promise.failure(e)
        }
      }(executionContext)

      promise.future
    }

    override def exchange(request: ExchangeRequest): Future[ExchangeReply] = {
      val futures = request.workers.map(workerMetadata =>
        Future {
          val host = workerMetadata.node.get.host
          val port = workerMetadata.node.get.port
          val partitionPath = Path(s"data/partition/${host}:${port}")

          try {
            if (partitionPath.exists) {
              val partition = Block.fromPath(partitionPath, 10, 90)
              val exchangeClient = ExchangeClient.apply(host, port)
              val reply = exchangeClient.saveRecords(partition.records)
              Some(reply)
            } else {
              None
            }
          } finally {
            if (partitionPath.exists) {
              partitionPath.delete()
            }
          }
        }(executionContext)
      )

      Future.sequence(futures).map(_ => new ExchangeReply())
    }

    override def merge(request: MergeRequest): Future[MergeReply] = {
      val promise = Promise[MergeReply]

      Future {
        try {
          val host = Path("data/host")
          val port = Path("data/port")
          val blockPath = Path(s"data/partition/${host}:${port}")
          val mergedBlock = Block.fromPath(blockPath, 10, 90).sort()
          mergedBlock.writeTo(blockPath)

          promise.success(new MergeReply())
        } catch {
          case e: Exception =>
            println(e)
            promise.failure(e)
        }
      }(executionContext)

      promise.future
    }
  }

}
