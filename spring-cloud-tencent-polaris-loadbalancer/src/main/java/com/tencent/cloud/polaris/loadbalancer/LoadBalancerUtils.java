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
 *
 */

package com.tencent.cloud.polaris.loadbalancer;

import java.util.List;
import java.util.stream.Collectors;

import com.tencent.cloud.common.metadata.MetadataContext;
import com.tencent.polaris.api.pojo.DefaultInstance;
import com.tencent.polaris.api.pojo.DefaultServiceInstances;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.ServiceInstances;
import com.tencent.polaris.api.pojo.ServiceKey;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.util.CollectionUtils;

/**
 * load balancer utils.
 *
 *@author lepdou 2022-05-17
 */
public class LoadBalancerUtils {

	public static ServiceInstances transferServersToServiceInstances(Flux<List<ServiceInstance>> servers) {
		List<Instance> instances = servers.toStream().flatMap(List::stream).map(serviceInstance -> {
			DefaultInstance instance = new DefaultInstance();
			instance.setNamespace(MetadataContext.LOCAL_NAMESPACE);
			instance.setService(serviceInstance.getServiceId());
			instance.setProtocol(serviceInstance.getScheme());
			instance.setId(serviceInstance.getInstanceId());
			instance.setHost(serviceInstance.getHost());
			instance.setPort(serviceInstance.getPort());
			instance.setWeight(100);
			instance.setMetadata(serviceInstance.getMetadata());
			return instance;
		}).collect(Collectors.toList());

		String serviceName = null;
		if (!CollectionUtils.isEmpty(instances)) {
			serviceName = instances.get(0).getService();
		}

		ServiceKey serviceKey = new ServiceKey(MetadataContext.LOCAL_NAMESPACE, serviceName);

		return new DefaultServiceInstances(serviceKey, instances);
	}
}
