/*
 * Copyright (C) 2014 Xiao-Long Chen <chillermillerlong@hotmail.com>
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

package com.infinityx.dialer.lookup.zabasearch;

import android.text.TextUtils;

import com.infinityx.dialer.lookup.LookupUtils;

import java.io.IOException;

public class ZabaSearchApi {
  private static final String TAG = ZabaSearchApi.class.getSimpleName();

  private static final String LOOKUP_URL = "https://www.zabasearch.com/phone/";

  private final String number;
  public String output = null;
  private ContactInfo info = null;

  public ZabaSearchApi(String number) {
    this.number = number;
  }

  private void fetchPage() throws IOException {
    output = LookupUtils.httpGet(LOOKUP_URL + number, null);
  }

  private void buildContactInfo() {
    // Extract values from the fixed itemprop attributes without regular expressions.
    String name = extractItemPropValue(output, "name", false);
    String phoneNumber = extractItemPropValue(output, "telephone", false);
    String addressStreet = extractItemPropValue(output, "streetAddress", true);
    String addressCity = extractItemPropValue(output, "addressLocality", false);
    String addressState = extractItemPropValue(output, "addressRegion", false);
    String addressZip = extractItemPropValue(output, "postalCode", false);

    StringBuilder sb = new StringBuilder();

    if (!TextUtils.isEmpty(addressStreet)) {
      sb.append(addressStreet);
    }
    if (!TextUtils.isEmpty(addressCity)) {
      sb.append(", ");
      sb.append(addressCity);
    }
    if (!TextUtils.isEmpty(addressState)) {
      sb.append(", ");
      sb.append(addressState);
    }
    if (!TextUtils.isEmpty(addressZip)) {
      sb.append(", ");
      sb.append(addressZip);
    }

    String address = sb.toString();
    if (address.isEmpty()) {
      address = null;
    }

    info = new ContactInfo();
    info.name = name;
    info.address = address;
    info.formattedNumber = number;
    info.website = LOOKUP_URL + info.formattedNumber;
  }

  private static String extractItemPropValue(
      String input, String property, boolean stripTrailingNbsp) {
    if (input == null) {
      return null;
    }

    String quotedMarker = "itemprop=\"" + property + "\">";
    String unquotedMarker = "itemprop=" + property + ">";
    int searchFrom = 0;

    while (searchFrom < input.length()) {
      int quotedIndex = input.indexOf(quotedMarker, searchFrom);
      int unquotedIndex = input.indexOf(unquotedMarker, searchFrom);

      int markerIndex;
      int markerLength;

      if (quotedIndex < 0 && unquotedIndex < 0) {
        return null;
      } else if (quotedIndex < 0
          || (unquotedIndex >= 0 && unquotedIndex < quotedIndex)) {
        markerIndex = unquotedIndex;
        markerLength = unquotedMarker.length();
      } else {
        markerIndex = quotedIndex;
        markerLength = quotedMarker.length();
      }

      int valueStart = markerIndex + markerLength;
      int valueEnd = input.indexOf('<', valueStart);

      if (valueEnd < 0) {
        return null;
      }

      String value = input.substring(valueStart, valueEnd).trim();

      if (stripTrailingNbsp) {
        while (value.endsWith("&nbsp;")) {
          value = value.substring(0, value.length() - "&nbsp;".length()).trim();
        }
      }

      if (!value.isEmpty()) {
        return value;
      }

      searchFrom = valueEnd + 1;
    }

    return null;
  }

  public ContactInfo getContactInfo() throws IOException {
    if (info == null) {
      fetchPage();
      buildContactInfo();
    }

    return info;
  }

  public static class ContactInfo {
    String name;
    String address;
    String formattedNumber;
    String website;
  }
}
