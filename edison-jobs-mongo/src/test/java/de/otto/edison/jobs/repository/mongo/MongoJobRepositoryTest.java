package de.otto.edison.jobs.repository.mongo;

import com.github.fakemongo.Fongo;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import de.otto.edison.jobs.domain.JobInfo;
import de.otto.edison.jobs.domain.JobInfo.JobStatus;
import de.otto.edison.jobs.domain.JobMessage;
import de.otto.edison.jobs.domain.Level;
import de.otto.edison.jobs.repository.JobBlockedException;
import org.bson.Document;
import org.hamcrest.Matchers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.util.*;

import static de.otto.edison.jobs.domain.JobInfo.JobStatus.OK;
import static de.otto.edison.jobs.domain.JobMessage.jobMessage;
import static de.otto.edison.jobs.repository.mongo.JobStructure.*;
import static de.otto.edison.jobs.repository.mongo.MongoJobRepository.RUNNING_JOBS_DOCUMENT;
import static de.otto.edison.testsupport.util.Sets.hashSet;
import static java.time.Clock.systemDefaultZone;
import static java.time.OffsetDateTime.now;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.testng.Assert.assertFalse;

public class MongoJobRepositoryTest {

    private MongoJobRepository repo;
    private MongoDatabase database;
    private MongoCollection<Document> runningJobsCollection;

    @BeforeMethod
    public void setup() {
        final Fongo fongo = new Fongo("inmemory-mongodb");
        database = fongo.getDatabase("jobsinfo");
        repo = new MongoJobRepository(database, systemDefaultZone());
        runningJobsCollection = database.getCollection(MongoJobRepository.RUNNING_JOBS_COLLECTION_NAME);
        repo.initRunningJobsDocumentOnStartup();
    }


    @Test
    public void shouldStoreAndRetrieveJobInfo() {
        // given
        final JobInfo foo = someJobInfo("http://localhost/foo/A");
        final JobInfo writtenFoo = repo.create(foo);
        // when
        final Optional<JobInfo> jobInfo = repo.findOne("http://localhost/foo/A");
        // then
        assertThat(jobInfo.isPresent(), is(true));
        assertThat(jobInfo.get(), is(writtenFoo));
    }

    @Test
    public void shouldUpdateJobInfo() {
        // given
        final JobInfo foo = someJobInfo("http://localhost/foo/B");
        repo.createOrUpdate(foo);
        final JobInfo writtenFoo = repo.createOrUpdate(foo.copy().addMessage(jobMessage(Level.INFO, "some message", now(foo.getClock()))).build());
        // when
        final Optional<JobInfo> jobInfo = repo.findOne("http://localhost/foo/B");
        // then
        assertThat(jobInfo.get(), is(writtenFoo));
    }

    @Test
    public void shouldStoreAndRetrieveRunningJobInfo() {
        // given
        final JobInfo foo = someRunningJobInfo("http://localhost/foo", "SOME_JOB", now());
        repo.createOrUpdate(foo);
        // when
        final Optional<JobInfo> jobInfo = repo.findOne("http://localhost/foo");
        // then
        assertThat(jobInfo.get(), is(foo));
    }

    @Test
    public void shouldStoreAndRetrieveAllJobInfo() {
        // given
        repo.createOrUpdate(someJobInfo("http://localhost/foo"));
        repo.createOrUpdate(someJobInfo("http://localhost/bar"));
        // when
        final List<JobInfo> jobInfos = repo.findAll();
        // then
        assertThat(jobInfos, hasSize(2));
    }

    @Test
    public void shouldFindLatest() {
        // given
        repo.createOrUpdate(someRunningJobInfo("http://localhost/foo", "SOME_JOB", now()));
        JobInfo later = someRunningJobInfo("http://localhost/bar", "SOME_JOB", now().plus(1, SECONDS));
        repo.createOrUpdate(later);
        JobInfo evenLater = someRunningJobInfo("http://localhost/foobar", "SOME_JOB", now().plus(2, SECONDS));
        repo.createOrUpdate(evenLater);
        // when
        final List<JobInfo> jobInfos = repo.findLatest(2);
        // then
        assertThat(jobInfos, hasSize(2));
        assertThat(jobInfos, containsInAnyOrder(later, evenLater));
    }

    @Test
    public void shouldFindLatestByType() {
        // given
        final JobInfo oldest = someRunningJobInfo("http://localhost/foo", "SOME_JOB", now());
        final JobInfo later = someRunningJobInfo("http://localhost/bar", "SOME_OTHER_JOB", now().plus(1, SECONDS));
        final JobInfo evenLater = someRunningJobInfo("http://localhost/foobar", "SOME_JOB", now().plus(2, SECONDS));
        repo.createOrUpdate(oldest);
        repo.createOrUpdate(later);
        repo.createOrUpdate(evenLater);
        // when
        final List<JobInfo> jobInfos = repo.findLatestBy("SOME_JOB", 2);
        // then
        assertThat(jobInfos, containsInAnyOrder(oldest, evenLater));
    }

    @Test
    public void shouldFindByType() {
        // given
        final JobInfo foo = someJobInfo("http://localhost/foo", "T_FOO");
        repo.createOrUpdate(foo);
        repo.createOrUpdate(someJobInfo("http://localhost/bar", "T_BAR"));
        // when
        final List<JobInfo> jobInfos = repo.findByType("T_FOO");
        // then
        assertThat(jobInfos, contains(foo));
    }

    @Test
    public void shouldFindRunningWithoutUpdateSince() {
        // given
        final JobInfo foo = someJobInfo("http://localhost/foo", "T_FOO");
        final JobInfo bar = someRunningJobInfo("http://localhost/bar", "T_BAR", now());
        final JobInfo foobar = someRunningJobInfo("http://localhost/foobar", "T_BAR", now().plusSeconds(3));
        repo.createOrUpdate(foo);
        repo.createOrUpdate(bar);
        repo.createOrUpdate(foobar);
        // when
        final List<JobInfo> infos = repo.findRunningWithoutUpdateSince(now().plusSeconds(2));
        // then
        assertThat(infos, hasSize(1));
        assertThat(infos.get(0), is(bar));
    }

    @Test
    public void shouldRemoveIfStopped() {
        // given
        final JobInfo foo = someJobInfo("foo", "T_FOO");
        final JobInfo bar = someRunningJobInfo("bar", "T_BAR", now());
        repo.createOrUpdate(foo);
        repo.createOrUpdate(bar);
        // when
        repo.removeIfStopped("foo");
        repo.removeIfStopped("bar");
        // then
        assertThat(repo.findAll(), hasSize(1));
        assertThat(repo.findAll(), contains(bar));
    }

    @Test
    public void shouldFindAllJobTypes() throws Exception {
        // Given
        OffsetDateTime now = OffsetDateTime.now();
        final JobInfo eins = someRunningJobInfo("jobEins", "someJobTypeEins", now);
        final JobInfo zwei = someRunningJobInfo("jobZwei", "someJobTypeZwei", now.plusSeconds(1));
        final JobInfo drei = someRunningJobInfo("jobDrei", "someJobTypeDrei", now.plusSeconds(2));
        final JobInfo vierWithTypeDrei = someRunningJobInfo("jobVier", "someJobTypeDrei", now.plusSeconds(3));

        repo.createOrUpdate(eins);
        repo.createOrUpdate(zwei);
        repo.createOrUpdate(drei);
        repo.createOrUpdate(vierWithTypeDrei);

        // When
        List<String> allJobIds = repo.findAllJobIdsDistinct();

        // Then
        assertThat(allJobIds, hasSize(3));
        assertThat(allJobIds, Matchers.containsInAnyOrder("jobEins", "jobZwei", "jobVier"));
    }

    @Test
    public void shouldFindLatestDistinct() throws Exception {
        // Given
        OffsetDateTime now = OffsetDateTime.now();
        final JobInfo eins = someRunningJobInfo("http://localhost/eins", "someJobType", now);
        final JobInfo zwei = someRunningJobInfo("http://localhost/zwei", "someOtherJobType", now.plusSeconds(1));
        final JobInfo drei = someRunningJobInfo("http://localhost/drei", "nextJobType", now.plusSeconds(2));
        final JobInfo vier = someRunningJobInfo("http://localhost/vier", "someJobType", now.plusSeconds(3));
        final JobInfo fuenf = someRunningJobInfo("http://localhost/fuenf", "someJobType", now.plusSeconds(4));

        repo.createOrUpdate(eins);
        repo.createOrUpdate(zwei);
        repo.createOrUpdate(drei);
        repo.createOrUpdate(vier);
        repo.createOrUpdate(fuenf);

        // When
        List<JobInfo> latestDistinct = repo.findLatestJobsDistinct();

        // Then
        assertThat(latestDistinct, hasSize(3));
        assertThat(latestDistinct, Matchers.containsInAnyOrder(fuenf, zwei, drei));
    }

    @Test
    public void shouldNotFailInCaseLogMessageHasNoText() throws Exception {
        //given
        Map<String, Object> infoLog = new HashMap<String, Object>() {{
            put(MSG_LEVEL.key(), "INFO");
            put(MSG_TEXT.key(), "Some text");
            put(MSG_TS.key(), new Date());
        }};

        Map<String, Object> errorLog = new HashMap<String, Object>() {{
            put(MSG_LEVEL.key(), "ERROR");
            put(MSG_TEXT.key(), null);
            put(MSG_TS.key(), new Date());
        }};

        Document infoLogDocument = new Document(infoLog);
        Document errorLogDocument = new Document(errorLog);

        Map<String, Object> jobLogs = new HashMap<String, Object>() {{
            put(MESSAGES.key(), asList(infoLogDocument, errorLogDocument));
            put(JOB_TYPE.key(), "SomeType");
            put(ID.key(), "/SomeType/ID");
            put(STATUS.key(), JobStatus.ERROR.toString());
        }};

        //when
        JobInfo jobInfo = repo.decode(new Document(jobLogs));

        //then
        assertThat(jobInfo.getMessages().size(), is(2));
    }

    @Test
    public void shouldFindStatusOfAJob() throws Exception {
        //Given
        final JobInfo foo = someJobInfo("http://localhost/foo", "T_FOO");
        repo.createOrUpdate(foo);

        //When
        JobStatus status = repo.findStatus("http://localhost/foo");

        //Then
        assertThat(status, is(OK));
    }

    @Test
    public void shouldAppendMessageToJob() throws Exception {
        // given
        String jobId = "http://localhost/baZ";
        JobInfo jobInfo = someJobInfo(jobId, "T_FOO");
        repo.createOrUpdate(jobInfo);

        // when
        JobMessage jobMessage = JobMessage.jobMessage(Level.INFO, "Schön ist es auf der Welt zu sein, sagt der Igel zu dem Stachelschwein", now());
        repo.appendMessage(jobId, jobMessage);

        // then
        JobInfo jobInfoFromDB = repo.findOne(jobId).get();
        assertThat(jobInfoFromDB.getMessages(), hasSize(3));
        assertThat(jobInfoFromDB.getMessages().get(2), is(jobMessage));
        assertThat(jobInfoFromDB.getStatus(), is(JobStatus.OK));

    }

    @Test
    public void shouldMarkJobAsRunning() throws Exception {
        final String jobType = "myJobType";
        JobInfo jobInfo = someJobInfo("", jobType);

        repo.markJobAsRunningIfPossible(jobInfo.getJobType(), hashSet(jobType));

        assertRunningDocumentContainsJob(jobType);
    }

    @Test(expectedExceptions = JobBlockedException.class)
    public void shouldNotStartJobIfAlreadyRunning() throws Exception {
        // given
        final String jobType = "myJobType";
        addJobToRunningDocument(jobType);

        // when
        try {
            repo.markJobAsRunningIfPossible(jobType, hashSet(jobType));
        }

        // then
        catch (JobBlockedException e) {
            assertRunningDocumentContainsJob(jobType);
            throw e;
        }
    }

    @Test(expectedExceptions = JobBlockedException.class)
    public void shouldNotStartJobIfBlockedByAnotherJob() throws Exception {
        // given
        final String jobType = "myJobType";
        final String otherJobType = "myOtherJobType";
        addJobToRunningDocument(otherJobType);

        // when
        try {
            repo.markJobAsRunningIfPossible(jobType, hashSet(jobType, otherJobType));
        }

        // then
        catch (JobBlockedException e) {
            assertRunningDocumentContainsJob(otherJobType);
            throw e;
        }
    }

    @Test
    public void shouldRemoveStoppedJobFromRunningDocument() {
        String jobType = "myJobType";
        String jobId = "jobID";
        addJobToRunningDocument("otherJobType");
        addJobToRunningDocument(jobType);

        repo.clearRunningMark(jobType);

        assertRunningDocumentNotContainsJob(jobType);
        assertRunningDocumentContainsJob("otherJobType");
    }

    private void addJobToRunningDocument(String jobType) {
        runningJobsCollection.updateOne(
                new Document("_id", RUNNING_JOBS_DOCUMENT),
                new Document("$set",
                        new Document(jobType, jobType))
        );
    }


    private void assertRunningDocumentContainsJob(String jobType) {
        Document magicDocument = runningJobsCollection.find(new Document("_id", RUNNING_JOBS_DOCUMENT)).iterator().next();
        assertThat(magicDocument.entrySet(), is(new HashMap() {{
            put("_id", RUNNING_JOBS_DOCUMENT);
            put(jobType, jobType);
        }}.entrySet()));
    }

    private void assertRunningDocumentNotContainsJob(String jobType) {
        Document magicDocument = runningJobsCollection.find(new Document("_id", RUNNING_JOBS_DOCUMENT)).iterator().next();
        assertThat(magicDocument.containsKey(jobType), is(false));
    }

    private JobInfo someJobInfo(final String jobId) {
        return JobInfo.newJobInfo(
                jobId,
                "SOME_JOB",
                now(), now(), Optional.of(now()), OK,
                asList(
                        jobMessage(Level.INFO, "foo", now()),
                        jobMessage(Level.WARNING, "bar", now())),
                systemDefaultZone(),
                "localhost"
        );
    }

    private JobInfo someJobInfo(final String jobId, final String type) {
        return JobInfo.newJobInfo(
                jobId,
                type,
                now(), now(), Optional.of(now()), OK,
                asList(
                        jobMessage(Level.INFO, "foo", now()),
                        jobMessage(Level.WARNING, "bar", now())),
                systemDefaultZone(),
                "localhost"
        );
    }

    private JobInfo someRunningJobInfo(final String jobId, final String type, final OffsetDateTime started) {
        return JobInfo.newJobInfo(
                jobId,
                type,
                started, started.plus(1, SECONDS), Optional.empty(), OK,
                Collections.<JobMessage>emptyList(),
                systemDefaultZone(),
                "localhost"
        );
    }
}