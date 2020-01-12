/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.eureka.server;

import javax.servlet.ServletContext;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.ConfigurationManager;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.V1AwareInstanceInfoConverter;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author Spencer Gibb
 * eurekaServerBootstrap是spring cloud定义的类，其代码完全拷贝了Eureka启动类的实现
 *
 * Eureka是一个纯正的Servlet应用，而Spring Boot使用的是嵌入式Tomcat, 因此就需要一定的胶水代码让Eureka跑在Embedded Tomcat中。
 * 这部分工作是在 EurekaServerBootstrap 中完成的。与上面提到的EurekaBootStrap相比，
 * 它的代码几乎是直接将原生代码copy过来的，虽然它并没有继承 ServletContextListener, 但是相应的生命周期方法都还在
 */
public class EurekaServerBootstrap {

	private static final Log log = LogFactory.getLog(EurekaServerBootstrap.class);

	private static final String TEST = "test";

	private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

	private static final String EUREKA_ENVIRONMENT = "eureka.environment";

	private static final String DEFAULT = "default";

	private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

	private static final String EUREKA_DATACENTER = "eureka.datacenter";

	protected EurekaServerConfig eurekaServerConfig;

	protected ApplicationInfoManager applicationInfoManager;

	protected EurekaClientConfig eurekaClientConfig;

	protected PeerAwareInstanceRegistry registry;

	protected volatile EurekaServerContext serverContext;
	protected volatile AwsBinder awsBinder;

	public EurekaServerBootstrap(ApplicationInfoManager applicationInfoManager,
			EurekaClientConfig eurekaClientConfig, EurekaServerConfig eurekaServerConfig,
			PeerAwareInstanceRegistry registry, EurekaServerContext serverContext) {
		this.applicationInfoManager = applicationInfoManager;
		this.eurekaClientConfig = eurekaClientConfig;
		this.eurekaServerConfig = eurekaServerConfig;
		this.registry = registry;
		this.serverContext = serverContext;
	}

	public void contextInitialized(ServletContext context) {//启动入口
		try {
			initEurekaEnvironment();// 初始化Eureka的环境变量
			initEurekaServerContext();// 初始化Eureka的上下文

			context.setAttribute(EurekaServerContext.class.getName(), this.serverContext);
		}
		catch (Throwable e) {
			log.error("Cannot bootstrap eureka server :", e);
			throw new RuntimeException("Cannot bootstrap eureka server :", e);
		}
	}

	public void contextDestroyed(ServletContext context) {
		try {
			log.info("Shutting down Eureka Server..");
			context.removeAttribute(EurekaServerContext.class.getName());

			destroyEurekaServerContext();
			destroyEurekaEnvironment();

		}
		catch (Throwable e) {
			log.error("Error shutting down eureka", e);
		}
		log.info("Eureka Service is now shutdown...");
	}

	protected void initEurekaEnvironment() throws Exception {
		log.info("Setting the eureka configuration..");

		String dataCenter = ConfigurationManager.getConfigInstance()
				.getString(EUREKA_DATACENTER);
		if (dataCenter == null) {
			log.info(
					"Eureka data center value eureka.datacenter is not set, defaulting to default");
			ConfigurationManager.getConfigInstance()
					.setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
		}
		else {
			ConfigurationManager.getConfigInstance()
					.setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
		}
		String environment = ConfigurationManager.getConfigInstance()
				.getString(EUREKA_ENVIRONMENT);
		if (environment == null) {
			ConfigurationManager.getConfigInstance()
					.setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
			log.info(
					"Eureka environment value eureka.environment is not set, defaulting to test");
		}
		else {
			ConfigurationManager.getConfigInstance()
					.setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, environment);
		}
	}

	protected void initEurekaServerContext() throws Exception {
		// For backward compatibility
		JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(),
				XStream.PRIORITY_VERY_HIGH);
		XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(),
				XStream.PRIORITY_VERY_HIGH);

		if (isAws(this.applicationInfoManager.getInfo())) {
			this.awsBinder = new AwsBinderDelegate(this.eurekaServerConfig,
					this.eurekaClientConfig, this.registry, this.applicationInfoManager);
			this.awsBinder.start();
		}

		EurekaServerContextHolder.initialize(this.serverContext);//初始化eureka server上下文

		log.info("Initialized server context");

		// Copy registry from neighboring eureka node // 从相邻的eureka节点复制注册表
		/**
		 egistry.syncUp() 方法用于在当前 Eureka Server 节点启动时从邻近的Eureka Server同步注册信息，
		 并返回同步得到的应用数量。当存在多个Eureka Server时，该方法会有实际的作用，
		 用于达到各个节点之间数据的最终一致性。可以通过 eureka.server.registry-sync-retries 配置调整同步重试次数。
		 如果未获取到应用实例，则 Eureka-Server 会有一段时间( 默认：5 分钟，可配 )不允许被 Eureka-Client 获取注册信息，
		 避免影响 Eureka-Client
		 */
		int registryCount = this.registry.syncUp();
		/**
		 registry.openForTraffic() 方法
		 在应用启动后，将会向Eureka Server发送心跳,默认周期为30秒，如果Eureka Server在多个心跳周期内没有接收到某个节点的心跳，
		 Eureka Server将会从服务注册表中把这个服务节点移除(默认90秒)。
		 */
		this.registry.openForTraffic(this.applicationInfoManager, registryCount);
// 默认每30秒发送心跳，1分钟就是2次
		// 修改eureka状态为up
		// 同时，这里面会开启一个定时任务，用于清理 60秒没有心跳的客户端。自动下线
		// Register all monitoring statistics.
		EurekaMonitors.registerAllStats();
	}

	/**
	 * Server context shutdown hook. Override for custom logic
	 */
	protected void destroyEurekaServerContext() throws Exception {
		EurekaMonitors.shutdown();
		if (this.awsBinder != null) {
			this.awsBinder.shutdown();
		}
		if (this.serverContext != null) {
			this.serverContext.shutdown();
		}
	}

	/**
	 * Users can override to clean up the environment themselves.
	 */
	protected void destroyEurekaEnvironment() throws Exception {
	}

	protected boolean isAws(InstanceInfo selfInstanceInfo) {
		boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo
				.getDataCenterInfo().getName();
		log.info("isAws returned " + result);
		return result;
	}

}
