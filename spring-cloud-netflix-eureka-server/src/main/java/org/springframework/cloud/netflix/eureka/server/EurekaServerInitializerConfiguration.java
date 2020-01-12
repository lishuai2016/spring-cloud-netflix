/*
 * Copyright 2013-2014 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.eureka.server.event.EurekaRegistryAvailableEvent;
import org.springframework.cloud.netflix.eureka.server.event.EurekaServerStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.context.ServletContextAware;

import com.netflix.eureka.EurekaServerConfig;

/**
 * @author Dave Syer
在EurekaServerAutoConfiguration加载完成之后就会执行EurekaServerInitializerConfiguration这个类的start方法

SmartLifecycle(Spring容器初始化该bean时会调用相应生命周期方法)
 */
@Configuration
public class EurekaServerInitializerConfiguration
		implements ServletContextAware, SmartLifecycle, Ordered {

	private static final Log log = LogFactory.getLog(EurekaServerInitializerConfiguration.class);

	@Autowired
	private EurekaServerConfig eurekaServerConfig;

	private ServletContext servletContext;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private EurekaServerBootstrap eurekaServerBootstrap;

	private boolean running;

	private int order = 1;

	@Override
	public void setServletContext(ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	@Override
	public void start() {//在Spring容器初始化该组件时，Spring调用其生命周期方法start()从而触发了Eureka的启动
		new Thread(new Runnable() {// 启动一个线程
			@Override
			public void run() {
				try {
					//TODO: is this class even needed now?  //初始化EurekaServer，同时启动Eureka Server
					eurekaServerBootstrap.contextInitialized(EurekaServerInitializerConfiguration.this.servletContext);
					log.info("Started Eureka Server");

					publish(new EurekaRegistryAvailableEvent(getEurekaServerConfig()));// 发布EurekaServer的注册事件
					EurekaServerInitializerConfiguration.this.running = true;// 设置启动的状态为true
					publish(new EurekaServerStartedEvent(getEurekaServerConfig())); // 发送Eureka Start 事件 ， 其他还有各种事件，我们可以监听这种时间，然后做一些特定的业务需求
				}
				catch (Exception ex) {
					// Help!
					log.error("Could not initialize Eureka servlet context", ex);
				}
			}
		}).start();
	}

	private EurekaServerConfig getEurekaServerConfig() {
		return this.eurekaServerConfig;
	}

	private void publish(ApplicationEvent event) {//发布事件
		this.applicationContext.publishEvent(event);
	}

	@Override
	public void stop() {
		this.running = false;
		eurekaServerBootstrap.contextDestroyed(this.servletContext);
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public int getPhase() {
		return 0;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void stop(Runnable callback) {
		callback.run();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}
