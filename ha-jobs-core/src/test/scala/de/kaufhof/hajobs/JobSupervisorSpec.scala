package de.kaufhof.hajobs

import com.datastax.driver.core.utils.UUIDs
import de.kaufhof.hajobs.testutils.MockInitializers._
import de.kaufhof.hajobs.testutils.StandardSpec
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class JobSupervisorSpec extends StandardSpec {

  private val lockRepository = mock[LockRepository]

  private val jobStatusRepository = mock[JobStatusRepository]

  override def beforeEach() {
    initializeLockRepo(lockRepository)
    reset(jobStatusRepository)
  }

  "update job state in JobSupervisor" should {
    val jobStatus = JobStatus(UUIDs.timeBased(), JobTypes.JobSupervisor, UUIDs.timeBased(), JobState.Running, JobResult.Pending, DateTime.now().minusMinutes(10), None)
    val jobStatusWithContent = jobStatus.copy(content = Some(Json.toJson("some json")))
    val jobManager = mock[JobManager]

    "change the state of failed jobs to FAILED" in {
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq.empty))
      val successful: Future[Map[JobType, List[JobStatus]]] = Future.successful(Map(JobTypes.JobSupervisor -> List(jobStatus)))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(successful)
      when(jobStatusRepository.updateJobState(any(), any())(any())).thenAnswer(futureIdentityAnswer())
      when(jobStatusRepository.get(any(), any(), anyBoolean())(any())).thenReturn(Future.successful(Some(jobStatusWithContent)))

      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)

      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      val jobExecution = sut.run()
      await(jobExecution.result)

      verify(jobStatusRepository, times(1)).updateJobState(jobStatusWithContent, JobState.Dead)
    }

    "not change the state of still running jobs" in {
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq(Lock(jobStatus.jobType.lockType, jobStatus.jobId))))
      val successful: Future[Map[JobType, List[JobStatus]]] = Future.successful(Map(JobTypes.JobSupervisor -> List(jobStatus)))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(successful)

      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)

      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      val jobExecution = sut.run()
      await(jobExecution.result)

      verify(jobStatusRepository, times(0)).updateJobState(any[JobStatus], any())(any())
    }

    "return error on Exception" in {
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq(Lock(jobStatus.jobType.lockType, jobStatus.jobId))))
      val failed: Future[Map[JobType, List[JobStatus]]] = Future.failed(new RuntimeException("error"))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(failed)

      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)

      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      val jobExecution = sut.run()
      val res = intercept[RuntimeException](await(jobExecution.result))

      res.getMessage should be("error")

    }
  }

  "retrigger job in JobSupervisor" should {
    val someJob = mock[Job]
    when(someJob.jobType).thenReturn(JobTypes.JobSupervisor)
    when(someJob.retriggerCount).thenReturn(2)

    val jobManager = mock[JobManager]
    when(jobManager.getJob(any[JobType])).thenReturn(someJob)
    when(jobManager.retriggerJob(any(), any())).thenReturn(Future.successful(Started(UUIDs.timeBased())))

    "do nothing if no JobStatus exist" in {
      val successful: Future[Map[JobType, List[JobStatus]]] = Future.successful(Map(JobTypes.JobSupervisor -> Nil))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(successful)
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq.empty))
      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)
      await(sut.run().result)
      verify(jobManager, times(0)).retriggerJob(any(), any())
    }

    "do nothing if one job of the last trigger id ended successfully (even if a trigger id earlier failed))" in {
      val job1 = JobStatus(UUIDs.timeBased(), JobTypes.JobSupervisor, UUIDs.timeBased(), JobState.Canceled, JobResult.Failed, DateTime.now.minusMillis(1))
      val job2 = JobStatus(UUIDs.timeBased(), JobTypes.JobSupervisor, UUIDs.timeBased(), JobState.Finished, JobResult.Success, DateTime.now)
      val successful: Future[Map[JobType, List[JobStatus]]] = Future.successful(Map(JobTypes.JobSupervisor -> List(job1, job2)))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(successful)
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq.empty))
      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)
      await(sut.run().result)
      verify(jobManager, times(0)).retriggerJob(any(), any())
    }

    "retrigger a job if no job of the last trigger was successful and retrigger size is not reached" in {
      val jobTypeDummy: JobType = JobType("dummy", LockType("dummy"))
      val job1 = JobStatus(UUIDs.timeBased(), jobTypeDummy, UUIDs.timeBased(), JobState.Canceled, JobResult.Failed, DateTime.now.minusMillis(1))
      val successful: Future[Map[JobType, List[JobStatus]]] = Future.successful(Map(jobTypeDummy -> List(job1)))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(successful)
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq.empty))
      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)
      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      await(sut.run().result)
      verify(jobManager, times(1)).retriggerJob(job1.jobType, job1.triggerId)
    }

    "do nothing if no job of the last trigger was successful and retrigger size is reached" in {
      val jobTypeDummy: JobType = JobType("dummy", LockType("dummy"))
      val job1 = JobStatus(UUIDs.timeBased(), jobTypeDummy, UUIDs.timeBased(), JobState.Failed, JobResult.Failed, DateTime.now.minusMillis(0))
      val job2 = job1.copy(jobStatusTs = DateTime.now.minusMillis(1), jobId = UUIDs.random())
      val job3 = job1.copy(jobStatusTs = DateTime.now.minusMillis(2), jobId = UUIDs.random())
      val job4 = job1.copy(jobStatusTs = DateTime.now.minusMillis(2), jobId = UUIDs.random())
      val successful: Future[Map[JobType, List[JobStatus]]] = Future.successful(Map(jobTypeDummy -> List(job1, job2, job3, job4)))
      when(jobStatusRepository.getMetadata(anyBoolean(), any())(any())).thenReturn(successful)
      when(lockRepository.getAll()(any())).thenReturn(Future.successful(Seq.empty))
      val sut = new JobSupervisor(jobManager, lockRepository, jobStatusRepository)
      implicit val context = JobContext(JobTypes.JobSupervisor, UUIDs.timeBased(), UUIDs.timeBased())
      await(sut.run().result)
      verify(jobManager, times(0)).retriggerJob(job1.jobType, job1.triggerId)
    }
  }
}
