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

package com.infinityx.dialer.lookup.yellowpages;

import android.text.TextUtils;

import com.infinityx.dialer.lookup.LookupUtils;

import java.io.IOException;

public class YellowPagesApi {
  private static final String TAG = YellowPagesApi.class.getSimpleName();

  static final String LOOKUP_URL_UNITED_STATES =
      "https://www.yellowpages.com/phone?phone_search_terms=";
  static final String LOOKUP_URL_CANADA =
      "https://www.yellowpages.ca/search/si/1/";

  private final String number;
  private String output = null;
  private ContactInfo info = null;
  private final String lookupUrl;

  public YellowPagesApi(String number, String lookupUrl) {
    this.number = number;
    this.lookupUrl = lookupUrl;
  }

  private void fetchPage() throws IOException {
    output = LookupUtils.httpGet(lookupUrl + number, null);
  }

  private String getPhotoUrl(String website) throws IOException {
    String page = LookupUtils.httpGet(website, null);
    String galleryRef =
        extractQuotedValueContaining(page, "href=\"", "gallery?lid=");
    if (galleryRef == null) {
      return null;
    }

    // Get first image.
    String galleryPage =
        LookupUtils.httpGet("https://www.yellowpages.com" + galleryRef, null);
    return extractQuotedValueAfterMarker(
        galleryPage, "\"type\":\"image\",\"src\":\"");
  }

  private String[] parseNameWebsiteUnitedStates() {
    if (output == null) {
      return new String[] { null, null };
    }

    int searchFrom = 0;
    while (true) {
      int anchorStart = output.indexOf("<a href=\"", searchFrom);
      if (anchorStart < 0) {
        break;
      }

      int hrefStart = anchorStart + 9;
      int hrefEnd = output.indexOf('"', hrefStart);
      int tagEnd = hrefEnd < 0 ? -1 : output.indexOf('>', hrefEnd);

      if (hrefEnd > hrefStart && tagEnd >= 0) {
        int classIndex = output.indexOf("class=\"url", hrefEnd);
        if (classIndex >= 0 && classIndex < tagEnd) {
          int textStart = tagEnd + 1;
          int textEnd = output.indexOf('<', textStart);

          if (textEnd > textStart) {
            String website = output.substring(hrefStart, hrefEnd).trim();
            String name = output.substring(textStart, textEnd).trim();
            return new String[] { name, website };
          }
        }
      }

      searchFrom = tagEnd >= 0 ? tagEnd + 1 : anchorStart + 9;
    }

    return new String[] { null, null };
  }

    private String[] parseNameWebsiteCanada() {
    if (output == null) {
      return new String[] { null, null };
    }

    String marker = "class=\"ypgListingTitleLink utagLink\"";
    int markerIndex = output.indexOf(marker);

    if (markerIndex < 0) {
      return new String[] { null, null };
    }

    int hrefStart = output.indexOf("href=\"", markerIndex + marker.length());
    if (hrefStart < 0) {
      return new String[] { null, null };
    }

    int valueStart = hrefStart + 6;
    int valueEnd = output.indexOf('"', valueStart);
    int tagEnd = valueEnd < 0 ? -1 : output.indexOf('>', valueEnd);

    if (valueEnd < 0 || tagEnd < 0) {
      return new String[] { null, null };
    }

    int spanStart = output.indexOf("<span", tagEnd + 1);
    if (spanStart < 0) {
      return new String[] { null, null };
    }

    int classStart = spanStart + 5;
    if (classStart >= output.length()
        || !Character.isWhitespace(output.charAt(classStart))) {
      return new String[] { null, null };
    }

    while (classStart < output.length()
        && Character.isWhitespace(output.charAt(classStart))) {
      classStart++;
    }

    String expectedClass = "class=\"listingTitle\"";
    if (!output.startsWith(expectedClass, classStart)) {
      return new String[] { null, null };
    }

    int spanTagEnd = classStart + expectedClass.length();
    if (spanTagEnd >= output.length() || output.charAt(spanTagEnd) != '>') {
      return new String[] { null, null };
    }

    int spanEnd = output.indexOf("</span>", spanTagEnd + 1);
    if (spanEnd < 0) {
      return new String[] { null, null };
    }

    String website = output.substring(valueStart, valueEnd).trim();
    String title = output.substring(spanStart, spanEnd + 7).trim();
    String name = LookupUtils.fromHtml(title);

    return new String[] {
        name,
        "https://www.yellowpages.ca" + website
    };
  }

    private String parseNumberUnitedStates() {
    return extractLineTextAfterMarker(output, "business-phone");
  }

  private String parseNumberCanada() {
    return extractDivContentByClass(output, "phoneNumber");
  }

  private String parseAddressUnitedStates() {
    String addressStreet = extractLineTextAfterMarker(output, "street-address");
    if (addressStreet != null && addressStreet.endsWith(",")) {
      addressStreet = addressStreet.substring(0, addressStreet.length() - 1);
    }

    String addressCity = extractLineTextAfterMarker(output, "locality");
    String addressState = extractLineTextAfterMarker(output, "region");
    String addressZip = extractLineTextAfterMarker(output, "postal-code");

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
    return address.isEmpty() ? null : address;
  }

  private String parseAddressCanada() {
    return LookupUtils.fromHtml(extractDivContentByClass(output, "address"));
  }

  private static String extractQuotedValueContaining(
      String input, String marker, String requiredPart) {
    if (input == null) {
      return null;
    }

    int searchFrom = 0;
    while (searchFrom < input.length()) {
      int markerIndex = input.indexOf(marker, searchFrom);
      if (markerIndex < 0) {
        return null;
      }

      int valueStart = markerIndex + marker.length();
      int valueEnd = input.indexOf('"', valueStart);

      if (valueEnd > valueStart) {
        String value = input.substring(valueStart, valueEnd).trim();
        int requiredIndex = value.indexOf(requiredPart);

        if (requiredIndex > 0
            && requiredIndex + requiredPart.length() < value.length()) {
          return value;
        }
      }

      searchFrom = valueStart;
    }

    return null;
  }

  private static String extractQuotedValueAfterMarker(String input, String marker) {
    if (input == null) {
      return null;
    }

    int searchFrom = 0;
    while (searchFrom < input.length()) {
      int markerIndex = input.indexOf(marker, searchFrom);
      if (markerIndex < 0) {
        return null;
      }

      int valueStart = markerIndex + marker.length();
      int valueEnd = input.indexOf('"', valueStart);

      if (valueEnd > valueStart) {
        return input.substring(valueStart, valueEnd).trim();
      }

      searchFrom = valueStart;
    }

    return null;
  }

  private static String extractLineTextAfterMarker(String input, String marker) {
    if (input == null) {
      return null;
    }

    int searchFrom = 0;
    while (searchFrom < input.length()) {
      int markerIndex = input.indexOf(marker, searchFrom);
      if (markerIndex < 0) {
        return null;
      }

      int tagEnd = input.indexOf('>', markerIndex + marker.length());
      if (tagEnd < 0) {
        return null;
      }

      int valueStart = tagEnd + 1;
      while (valueStart < input.length() && input.charAt(valueStart) == '\n') {
        valueStart++;
      }

      int valueEnd = valueStart;
      while (valueEnd < input.length()
          && input.charAt(valueEnd) != '\n'
          && input.charAt(valueEnd) != '<') {
        valueEnd++;
      }

      int closingTagStart = valueEnd;
      while (closingTagStart < input.length()
          && input.charAt(closingTagStart) == '\n') {
        closingTagStart++;
      }

      if (valueEnd > valueStart
          && closingTagStart < input.length()
          && input.charAt(closingTagStart) == '<') {
        return input.substring(valueStart, valueEnd).trim();
      }

      searchFrom = markerIndex + marker.length();
    }

    return null;
  }

  private static String extractDivContentByClass(String input, String className) {
    if (input == null) {
      return null;
    }

    String marker = "class=\"" + className + "\"";
    int searchFrom = 0;

    while (searchFrom < input.length()) {
      int classIndex = input.indexOf(marker, searchFrom);
      if (classIndex < 0) {
        return null;
      }

      int divStart = input.lastIndexOf("<div", classIndex);
      int prefixStart = divStart < 0 ? -1 : divStart + 4;
      int classEnd = classIndex + marker.length();

      if (prefixStart >= 0
          && classIndex > prefixStart
          && isWhitespaceOnly(input, prefixStart, classIndex)
          && input.startsWith(">", classEnd)) {
        int contentStart = classEnd + 1;
        int contentEnd = input.indexOf("</div>", contentStart);

        if (contentEnd >= 0) {
          return input.substring(contentStart, contentEnd).trim();
        }
      }

      searchFrom = classEnd;
    }

    return null;
  }

  private static boolean isWhitespaceOnly(String input, int start, int end) {
    for (int i = start; i < end; i++) {
      if (!Character.isWhitespace(input.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private void buildContactInfo() throws IOException {

    String name = null;
    String website = null;
    String phoneNumber = null;
    String address = null;
    String photoUrl = null;

    if (lookupUrl.equals(LOOKUP_URL_UNITED_STATES)) {
      String[] ret = parseNameWebsiteUnitedStates();
      name = ret[0];
      website = ret[1];
      phoneNumber = parseNumberUnitedStates();
      address = parseAddressUnitedStates();
      if (website != null) {
        photoUrl = getPhotoUrl(website);
      }
    } else {
      String[] ret = parseNameWebsiteCanada();
      name = ret[0];
      website = ret[1];
      phoneNumber = parseNumberCanada();
      address = parseAddressCanada();
      // AFAIK, Canada's YellowPages doesn't have photos
    }

    info = new ContactInfo();
    info.name = name;
    info.address = address;
    info.formattedNumber = phoneNumber != null ? phoneNumber : number;
    info.website = website;
    info.photoUrl = photoUrl;
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
    String photoUrl;
  }
}
