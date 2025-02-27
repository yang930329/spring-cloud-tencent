/*
 * Tencent is pleased to support the open source community by making Spring Cloud Tencent available.
 *
 *  Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 *  Licensed under the BSD 3-Clause License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/BSD-3-Clause
 *
 *  Unless required by applicable law or agreed to in writing, software distributed
 *  under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 *  CONDITIONS OF ANY KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations under the License.
 *
 */

package com.tencent.cloud.common.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.util.StringUtils;

/**
 * the utils of parse address.
 *
 * @author lepdou 2022-03-29
 */
public final class AddressUtils {

	private static final String ADDRESS_SEPARATOR = ",";

	private AddressUtils() {

	}

	public static List<String> parseAddressList(String addressInfo) {
		if (!StringUtils.hasText(addressInfo)) {
			return Collections.emptyList();
		}
		List<String> addressList = new ArrayList<>();
		String[] addresses = addressInfo.split(ADDRESS_SEPARATOR);
		for (String address : addresses) {
			URI uri = URI.create(address.trim());
			addressList.add(uri.getAuthority());
		}
		return addressList;
	}

}
