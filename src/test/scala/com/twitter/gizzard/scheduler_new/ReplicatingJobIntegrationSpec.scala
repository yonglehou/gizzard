package com.twitter.gizzard.scheduler

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.specs.mock.{ClassMocker, JMocker}
import net.lag.configgy.{Config => CConfig}
import com.twitter.util.TimeConversions._
import thrift.{JobInjectorService, TThreadServer, JobInjector}
import nameserver.{Host, HostStatus, JobRelay}

object ReplicatingJobIntegrationSpec extends ConfiguredSpecification with JMocker with ClassMocker {
  "ReplicatingJobIntegration" should {
    // TODO: make configurable
    val port  = 12313
    val host  = Host("localhost", port, "c1", HostStatus.Normal)
    val relay = new JobRelay(Map("c1" -> List(host)), 1, false, 1.second)
    val codec = new ReplicatingJsonCodec(relay, { badJob =>
      println(new String(badJob, "UTF-8"))
    })

    var jobsApplied = new AtomicInteger

    val testJobParser = new JsonJobParser[JsonJob] {
      def apply(json: Map[String, Any]) = new JsonJob {
        override def className = "TestJob"
        def apply() { jobsApplied.incrementAndGet }
        def toMap = json
      }
    }
    codec += "TestJob".r -> testJobParser

    val schedulerConfig = new gizzard.config.Scheduler {
      val name = "tbird_test_q"
      val schedulerType = new gizzard.config.KestrelScheduler {
        val queuePath = "/tmp"
      }

      errorLimit = 10
    }

    val scheduler = new PrioritizingJobScheduler(Map(
      1 -> schedulerConfig(codec)
    ))

    val service   = new JobInjectorService[JsonJob](codec, scheduler)
    val processor = new JobInjector.Processor(service)
    val server    = TThreadServer("injector", port, 500, TThreadServer.makeThreadPool("injector", 5), processor)

    doBefore {
      server.start()
      scheduler.start()
    }

    doAfter {
      server.stop()
      scheduler.shutdown()
      new File("/tmp/tbird_test_q").delete()
      new File("/tmp/tbird_test_q_errors").delete()
    }

    "replicate and replay jobs" in {
      val testJob = testJobParser(Map("dummy" -> 1, "job" -> true, "blah" -> "blop"))
      scheduler.put(1, testJob)

      jobsApplied.get must eventually(be_==(2))
    }
  }
}

