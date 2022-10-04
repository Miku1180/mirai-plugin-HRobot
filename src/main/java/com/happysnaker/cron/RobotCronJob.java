package com.happysnaker.cron;

import com.happysnaker.HelloJob;
import com.happysnaker.config.RobotConfig;
import com.happysnaker.exception.CanNotParseCommandException;
import com.happysnaker.utils.MapGetter;
import com.happysnaker.utils.OfUtil;
import com.happysnaker.utils.RobotUtil;
import com.happysnaker.utils.StringUtil;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Contact;
import net.mamoe.mirai.message.data.MessageChain;
import org.quartz.*;
import org.quartz.impl.StdScheduler;
import org.quartz.impl.StdSchedulerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 机器人后台定时线程，每 3 分钟执行一次，用户可向此类提交后台任务
 * <p><strong>机器人应该只有一个 {@link org.quartz.Scheduler}，若想要自定义定时任务，可调用此类的 {@link #submitCronJob(Class, ScheduleBuilder, JobDataMap)} 方法</strong></p>
 *
 * @author Happysnaker
 * @description
 * @date 2022/7/2
 * @email happysnaker@foxmail.com
 */
public class RobotCronJob implements Job {
    /**
     * 后台线程执行的任务列表
     */
    public volatile static CopyOnWriteArrayList<Runnable> tasks = new CopyOnWriteArrayList<>();
    /**
     * 定时调度服务
     *
     * @see #scheduler
     * @deprecated 引入 {@link org.quartz.Scheduler} 后，此服务将逐渐放弃使用
     */
    @Deprecated
    public volatile static Timer service = new Timer();
    /**
     * 定时调度器
     *
     * @since v3.3
     */
    public volatile static org.quartz.Scheduler scheduler;
    public static Set<Long> visited;
    public static final int PERIOD_MINUTE = 3;

    static {
        try {
            scheduler = StdSchedulerFactory.getDefaultScheduler();
            // 涉及到时间调度，默认使用中国标准时间
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            } catch (Exception ignore) {
            }
            scheduler.start();
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<SubscribeCronJob> parseSubscribeCronJobFromConfig() throws CanNotParseCommandException {
        List<SubscribeCronJob> ret = new ArrayList<>();
        for (Map<String, Object> sub : RobotConfig.subscribe) {
            MapGetter mg = new MapGetter(sub);
            if ("bilibili".equals(mg.getString("platform", true))) {
                ret.add(new BilibiliSubscribeCronJob(mg));
            } else {
                throw new CanNotParseCommandException("无法解析的配置");
            }
        }
        return ret;
    }

    public static void cron() throws Exception {
        submitCronJob(RobotCronJob.class,
                SimpleScheduleBuilder.repeatMinutelyForever(PERIOD_MINUTE), null
        );
        // 注册后台任务
        addCronTask(parseSubscribeCronJobFromConfig());
    }

    /**
     * 执行用户自定义定期任务，此方法必须得等到机器人初始化完成后调用
     */
    public static void runCustomerPeriodTask(Bot instance) throws Exception {
        if (visited.contains(instance.getId())) {
            return;
        }
        visited.add(instance.getId());
        // 执行定时任务
        for (Map<String, Object> map : RobotConfig.periodicTask) {
            MapGetter mg = new MapGetter(map);
            long gid = mg.getLong("groupId");
            int count = mg.getInt("count") <= 0 ? Integer.MAX_VALUE : mg.getInt("count");
            boolean plusImage = mg.getBoolean("image");
            Contact contact = instance.getGroups().getOrFail(gid);
            List<String> contents = mg.getListOrSingleton("content", String.class);
            List<MessageChain> messages = new ArrayList<>();
            contents.forEach(c -> messages.add(RobotUtil.parseMiraiCode(c)));
            if (instance.getGroups().contains(gid)) {
                if (!StringUtil.isNullOrEmpty(mg.getString("cron"))) {
                    RobotUtil.submitSendMsgTask(mg.getString("cron"), count, plusImage, messages, contact);
                } else {
                    RobotUtil.submitSendMsgTask(mg.getInt("hour"), mg.getInt("minute"), count, plusImage, messages, contact);
                }
            }
        }
    }

    public static void submitCronJob(Class<? extends Job> c, ScheduleBuilder<? extends Trigger> scheduleBuilder, JobDataMap data) throws SchedulerException {
        if (data == null) {
            data = new JobDataMap();
        }
        JobDetail jobDetail = JobBuilder.newJob(c)
                .usingJobData(data)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .startNow()
                .withSchedule(scheduleBuilder)
                .build();
        scheduler.scheduleJob(jobDetail, trigger);
        RobotConfig.logger.info(String.format("提交任务 %s，下一次执行时间 %s", jobDetail.getKey().toString(), trigger.getNextFireTime().toString()));
    }

    public static void addCronTask(Runnable task) {
        tasks.add(task);
    }


    public static void addCronTask(List<? extends Runnable> tasks) {
        RobotCronJob.tasks.addAll(tasks);
    }

    public static void rmCronTask(Runnable task) {
        tasks.remove(task);
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            if (!RobotConfig.enableRobot) {
                return;
            }
            RobotConfig.logger.info(new Date() + ", run robot cron job...");
            for (Runnable task : tasks) {
                task.run();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}