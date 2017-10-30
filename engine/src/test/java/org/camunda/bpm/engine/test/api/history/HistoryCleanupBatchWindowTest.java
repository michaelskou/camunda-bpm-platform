package org.camunda.bpm.engine.test.api.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.time.DateUtils;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.history.HistoricCaseInstance;
import org.camunda.bpm.engine.history.HistoricDecisionInstance;
import org.camunda.bpm.engine.history.HistoricIncident;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.metrics.Meter;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricIncidentEntity;
import org.camunda.bpm.engine.impl.persistence.entity.JobEntity;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.util.ProcessEngineBootstrapRule;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 *
 * @author Anna Pazola
 *
 */
@RunWith(Parameterized.class)
@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoryCleanupBatchWindowTest {

  protected String defaultStartTime;
  protected String defaultEndTime;
  protected int defaultBatchSize;

  protected ProcessEngineBootstrapRule bootstrapRule = new ProcessEngineBootstrapRule() {
    public ProcessEngineConfiguration configureEngine(ProcessEngineConfigurationImpl configuration) {
      configuration.setHistoryCleanupBatchSize(20);
      configuration.setHistoryCleanupBatchThreshold(10);
      configuration.setDefaultNumberOfRetries(5);
      return configuration;
    }
  };

  protected ProvidedProcessEngineRule engineRule = new ProvidedProcessEngineRule(bootstrapRule);
  public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(bootstrapRule).around(engineRule).around(testRule);

  private HistoryService historyService;
  private ManagementService managementService;
  private ProcessEngineConfigurationImpl processEngineConfiguration;

  private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
  private static SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
  private static String currentDay = dayFormat.format(new Date());
  private static String nextDay = dayFormat.format(DateUtils.addDays(new Date(), 1));

  @Parameterized.Parameter(0)
  public String startTime;

  @Parameterized.Parameter(1)
  public String endTime;

  @Parameterized.Parameter(2)
  public Date startDateForCheck;

  @Parameterized.Parameter(3)
  public Date endDateForCheck;

  @Parameterized.Parameter(4)
  public Date currentDate;

  @Parameterized.Parameters
  public static Collection<Object[]> scenarios() throws ParseException {
    return Arrays.asList(new Object[][] {
        // inside the batch window on the same day
        { "22:00", "23:00", sdf.parse(currentDay + "T22:00:00"), sdf.parse(currentDay+"T23:00:00"), sdf.parse(currentDay + "T22:15:00")} ,
        // inside the batch window on the next day
        { "23:00", "01:00", sdf.parse(currentDay + "T23:00:00"), sdf.parse(nextDay + "T01:00:00"), sdf.parse(nextDay + "T00:15:00")},
        // batch window 24h
        { "00:00", "00:00", sdf.parse(currentDay + "T00:00:00"), sdf.parse(nextDay + "T00:00:00"), sdf.parse(currentDay + "T15:00:00")},
        // batch window 24h
        { "00:00", "00:00", sdf.parse(currentDay + "T00:00:00"), sdf.parse(nextDay + "T00:00:00"), sdf.parse(currentDay + "T00:00:00")},
        // before the batch window on the same day
        { "22:00", "23:00", sdf.parse(currentDay + "T22:00:00"), sdf.parse(currentDay + "T23:00:00"), sdf.parse(currentDay + "T21:15:00")},
        // after the batch window on the same day
        { "22:00", "23:00", sdf.parse(nextDay + "T22:00:00"), sdf.parse(nextDay + "T23:00:00"), sdf.parse(currentDay + "T23:15:00")},
        // after the batch window on the next day
        { "22:00", "23:00", sdf.parse(nextDay + "T22:00:00"), sdf.parse(nextDay + "T23:00:00"), sdf.parse(nextDay + "T00:15:00")} });
  }

  @Before
  public void init() {
    historyService = engineRule.getHistoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    managementService = engineRule.getManagementService();
    testRule.deploy("org/camunda/bpm/engine/test/api/oneTaskProcess.bpmn20.xml", "org/camunda/bpm/engine/test/api/dmn/Example.dmn", "org/camunda/bpm/engine/test/api/cmmn/oneTaskCaseWithHistoryTimeToLive.cmmn");

    defaultStartTime = processEngineConfiguration.getHistoryCleanupBatchWindowStartTime();
    defaultEndTime = processEngineConfiguration.getHistoryCleanupBatchWindowEndTime();
    defaultBatchSize = processEngineConfiguration.getHistoryCleanupBatchSize();
  }

  @After
  public void clearDatabase() {
    //reset configuration changes
    processEngineConfiguration.setHistoryCleanupBatchWindowStartTime(defaultStartTime);
    processEngineConfiguration.setHistoryCleanupBatchWindowEndTime(defaultEndTime);
    processEngineConfiguration.setHistoryCleanupBatchSize(defaultBatchSize);

    processEngineConfiguration.getCommandExecutorTxRequired().execute(new Command<Void>() {
      public Void execute(CommandContext commandContext) {

        List<Job> jobs = managementService.createJobQuery().list();
        if (jobs.size() > 0) {
          assertEquals(1, jobs.size());
          String jobId = jobs.get(0).getId();
          commandContext.getJobManager().deleteJob((JobEntity) jobs.get(0));
          commandContext.getHistoricJobLogManager().deleteHistoricJobLogByJobId(jobId);
        }

        List<HistoricIncident> historicIncidents = historyService.createHistoricIncidentQuery().list();
        for (HistoricIncident historicIncident : historicIncidents) {
          commandContext.getDbEntityManager().delete((HistoricIncidentEntity) historicIncident);
        }

        commandContext.getMeterLogManager().deleteAll();

        return null;
      }
    });

    List<HistoricProcessInstance> historicProcessInstances = historyService.createHistoricProcessInstanceQuery().list();
    for (HistoricProcessInstance historicProcessInstance: historicProcessInstances) {
      historyService.deleteHistoricProcessInstance(historicProcessInstance.getId());
    }

    List<HistoricDecisionInstance> historicDecisionInstances = historyService.createHistoricDecisionInstanceQuery().list();
    for (HistoricDecisionInstance historicDecisionInstance: historicDecisionInstances) {
      historyService.deleteHistoricDecisionInstanceByInstanceId(historicDecisionInstance.getId());
    }

    List<HistoricCaseInstance> historicCaseInstances = historyService.createHistoricCaseInstanceQuery().list();
    for (HistoricCaseInstance historicCaseInstance: historicCaseInstances) {
      historyService.deleteHistoricCaseInstance(historicCaseInstance.getId());
    }

    clearMetrics();

  }

  protected void clearMetrics() {
    Collection<Meter> meters = processEngineConfiguration.getMetricsRegistry().getMeters().values();
    for (Meter meter : meters) {
      meter.getAndClear();
    }
    managementService.deleteMetrics(null);
  }

  @Test
  public void testScheduleJobForBatchWindow() throws ParseException {
    processEngineConfiguration.setHistoryCleanupBatchWindowStartTime(startTime);
    processEngineConfiguration.setHistoryCleanupBatchWindowEndTime(endTime);

    processEngineConfiguration.initHistoryCleanup();

    ClockUtil.setCurrentTime(currentDate);

    Job job = historyService.cleanUpHistoryAsync();

    assertFalse(startDateForCheck.after(job.getDuedate())); // job due date is not before start date
    assertTrue(endDateForCheck.after(job.getDuedate()));
  }
}
