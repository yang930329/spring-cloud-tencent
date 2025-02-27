/*
 * Tencent is pleased to support the open source community by making Spring Cloud Tencent available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.cloud.polaris.router;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tencent.cloud.common.metadata.MetadataContext;
import com.tencent.cloud.common.metadata.MetadataContextHolder;
import com.tencent.cloud.common.pojo.PolarisServiceInstance;
import com.tencent.cloud.common.util.JacksonUtils;
import com.tencent.cloud.polaris.loadbalancer.LoadBalancerUtils;
import com.tencent.cloud.polaris.router.config.PolarisMetadataRouterProperties;
import com.tencent.cloud.polaris.router.config.PolarisNearByRouterProperties;
import com.tencent.cloud.polaris.router.config.PolarisRuleBasedRouterProperties;
import com.tencent.cloud.polaris.router.resttemplate.PolarisLoadBalancerRequest;
import com.tencent.polaris.api.exception.ErrorCode;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.ServiceInfo;
import com.tencent.polaris.api.pojo.ServiceInstances;
import com.tencent.polaris.plugins.router.metadata.MetadataRouter;
import com.tencent.polaris.plugins.router.nearby.NearbyRouter;
import com.tencent.polaris.plugins.router.rule.RuleBasedRouter;
import com.tencent.polaris.router.api.core.RouterAPI;
import com.tencent.polaris.router.api.rpc.ProcessRoutersRequest;
import com.tencent.polaris.router.api.rpc.ProcessRoutersResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequestContext;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.util.CollectionUtils;

/**
 * Service routing entrance.
 *
 * Rule routing needs to rely on request parameters for server filtering.
 * The interface cannot obtain the context object of the request granularity,
 * so the routing capability cannot be achieved through ServerListFilter.
 *
 * And {@link PolarisRouterServiceInstanceListSupplier#get(Request)} provides the ability to pass in http headers,
 * so routing capabilities are implemented through IRule.
 *
 * @author Haotian Zhang, lepdou
 */
public class PolarisRouterServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

	private static final Logger LOGGER = LoggerFactory.getLogger(PolarisRouterServiceInstanceListSupplier.class);

	private final PolarisNearByRouterProperties polarisNearByRouterProperties;
	private final PolarisMetadataRouterProperties polarisMetadataRouterProperties;
	private final PolarisRuleBasedRouterProperties polarisRuleBasedRouterProperties;
	private final RouterAPI routerAPI;

	public PolarisRouterServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
			RouterAPI routerAPI,
			PolarisNearByRouterProperties polarisNearByRouterProperties,
			PolarisMetadataRouterProperties polarisMetadataRouterProperties,
			PolarisRuleBasedRouterProperties polarisRuleBasedRouterProperties) {
		super(delegate);
		this.routerAPI = routerAPI;
		this.polarisNearByRouterProperties = polarisNearByRouterProperties;
		this.polarisMetadataRouterProperties = polarisMetadataRouterProperties;
		this.polarisRuleBasedRouterProperties = polarisRuleBasedRouterProperties;
	}

	@Override
	public Flux<List<ServiceInstance>> get() {
		throw new PolarisException(ErrorCode.INTERNAL_ERROR, "Unsupported method.");
	}

	@Override
	public Flux<List<ServiceInstance>> get(Request request) {
		// 1. get all servers
		Flux<List<ServiceInstance>> allServers = getDelegate().get();

		// 2. filter by router
		DefaultRequestContext requestContext = (DefaultRequestContext) request.getContext();
		PolarisRouterContext key = null;
		if (requestContext instanceof RequestDataContext) {
			key = buildRouterContext(((RequestDataContext) requestContext).getClientRequest().getHeaders());
		}
		else if (requestContext.getClientRequest() instanceof PolarisLoadBalancerRequest) {
			key = buildRouterContext(((PolarisLoadBalancerRequest<?>) requestContext.getClientRequest()).getRequest()
					.getHeaders());
		}
		return doRouter(allServers, key);
	}

	//set method to public for unit test
	PolarisRouterContext buildRouterContext(HttpHeaders headers) {
		Collection<String> labelHeaderValues = headers.get(RouterConstants.ROUTER_LABEL_HEADER);

		if (CollectionUtils.isEmpty(labelHeaderValues)) {
			return null;
		}

		PolarisRouterContext routerContext = new PolarisRouterContext();

		routerContext.setLabels(PolarisRouterContext.TRANSITIVE_LABELS, MetadataContextHolder.get()
				.getFragmentContext(MetadataContext.FRAGMENT_TRANSITIVE));

		labelHeaderValues.forEach(labelHeaderValue -> {
			try {
				Map<String, String> labels = JacksonUtils.deserialize2Map(URLDecoder.decode(labelHeaderValue, "UTF-8"));
				if (!CollectionUtils.isEmpty(labels)) {
					routerContext.setLabels(PolarisRouterContext.RULE_ROUTER_LABELS, labels);
				}
			}
			catch (UnsupportedEncodingException e) {
				LOGGER.error("Decode header[{}] failed.", labelHeaderValue, e);
				throw new RuntimeException(e);
			}
		});

		return routerContext;
	}

	Flux<List<ServiceInstance>> doRouter(Flux<List<ServiceInstance>> allServers, PolarisRouterContext key) {
		ServiceInstances serviceInstances = LoadBalancerUtils.transferServersToServiceInstances(allServers);

		// filter instance by routers
		ProcessRoutersRequest processRoutersRequest = buildProcessRoutersRequest(serviceInstances, key);

		ProcessRoutersResponse processRoutersResponse = routerAPI.processRouters(processRoutersRequest);

		List<ServiceInstance> filteredInstances = new ArrayList<>();
		ServiceInstances filteredServiceInstances = processRoutersResponse.getServiceInstances();
		for (Instance instance : filteredServiceInstances.getInstances()) {
			filteredInstances.add(new PolarisServiceInstance(instance));
		}
		return Flux.fromIterable(Collections.singletonList(filteredInstances));
	}

	ProcessRoutersRequest buildProcessRoutersRequest(ServiceInstances serviceInstances, PolarisRouterContext key) {
		ProcessRoutersRequest processRoutersRequest = new ProcessRoutersRequest();
		processRoutersRequest.setDstInstances(serviceInstances);

		// metadata router
		if (polarisMetadataRouterProperties.isEnabled()) {
			Map<String, String> transitiveLabels = getRouterLabels(key, PolarisRouterContext.TRANSITIVE_LABELS);
			processRoutersRequest.putRouterMetadata(MetadataRouter.ROUTER_TYPE_METADATA, transitiveLabels);
		}

		// nearby router
		if (polarisNearByRouterProperties.isEnabled()) {
			Map<String, String> nearbyRouterMetadata = new HashMap<>();
			nearbyRouterMetadata.put(NearbyRouter.ROUTER_ENABLED, "true");
			processRoutersRequest.putRouterMetadata(NearbyRouter.ROUTER_TYPE_NEAR_BY, nearbyRouterMetadata);
		}

		// rule based router
		// set dynamic switch for rule based router
		boolean ruleBasedRouterEnabled = polarisRuleBasedRouterProperties.isEnabled();
		Map<String, String> ruleRouterMetadata = new HashMap<>();
		ruleRouterMetadata.put(RuleBasedRouter.ROUTER_ENABLED, String.valueOf(ruleBasedRouterEnabled));
		processRoutersRequest.putRouterMetadata(RuleBasedRouter.ROUTER_TYPE_RULE_BASED, ruleRouterMetadata);

		ServiceInfo serviceInfo = new ServiceInfo();
		serviceInfo.setNamespace(MetadataContext.LOCAL_NAMESPACE);
		serviceInfo.setService(MetadataContext.LOCAL_SERVICE);

		if (ruleBasedRouterEnabled) {
			Map<String, String> ruleRouterLabels = getRouterLabels(key, PolarisRouterContext.RULE_ROUTER_LABELS);
			// The label information that the rule based routing depends on
			// is placed in the metadata of the source service for transmission.
			// Later, can consider putting it in routerMetadata like other routers.
			serviceInfo.setMetadata(ruleRouterLabels);
		}

		processRoutersRequest.setSourceService(serviceInfo);

		return processRoutersRequest;
	}

	private Map<String, String> getRouterLabels(PolarisRouterContext key, String type) {
		if (key != null) {
			return key.getLabels(type);
		}
		return Collections.emptyMap();
	}
}
