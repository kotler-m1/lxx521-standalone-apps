/*
 * Copyright (C) 2014 Danny Baumann <dannybaumann@web.de>
 * Copyright (C) 2023 The LineageOS Project
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

package com.infinityx.dialer.lookup.dastelefonbuch;

import android.content.Context;
import android.net.Uri;

import com.infinityx.dialer.lookup.LookupUtils;

import java.io.IOException;

public class TelefonbuchApi {
  private static final String TAG = TelefonbuchApi.class.getSimpleName();

  private static final String REVERSE_LOOKUP_URL =
          "https://www.dastelefonbuch.de/R%C3%BCckw%C3%A4rts-Suche/";

  private TelefonbuchApi() {
  }

  public static ContactInfo reverseLookup(Context context, String number) throws IOException {
    Uri uri = Uri.parse(REVERSE_LOOKUP_URL)
        .buildUpon()
        .appendPath(number)
        .build();

    String page = LookupUtils.httpGet(uri.toString(), null);
    String output = extractRelevantContent(page);

    String name = LookupUtils.fromHtml(
        extractBetween(output, "<div class=\"name\" title=\"", "\">"));
    if (name == null) {
      return null;
    }

    String phoneNumberValue =
        extractBetween(output, "<!-- phoneTo: ", " -->");
    if (phoneNumberValue != null) {
      phoneNumberValue = removeHiddenSpans(phoneNumberValue);
      phoneNumberValue = removeSpanTags(phoneNumberValue);
    }
    String phoneNumber = LookupUtils.fromHtml(phoneNumberValue);

    String address = extractAddress(output);
    String website = extractWebsite(output);

    ContactInfo info = new ContactInfo();
    info.name = name;
    info.address = address;
    info.formattedNumber = phoneNumber != null ? phoneNumber : number;
    info.website = website != null ? website : uri.toString();

    return info;
  }

  private static String extractRelevantContent(String page) {
    if (page == null) {
      return null;
    }

    String startMarker = "<div class=\"vcard\">";
    String endMarker = "<div class=\"additional\">";

    int start = page.indexOf(startMarker);
    int end = page.lastIndexOf(endMarker);

    if (start < 0 || end < start + startMarker.length()) {
      return null;
    }

    return page.substring(start + startMarker.length(), end);
  }

  private static String extractBetween(
      String input, String startMarker, String endMarker) {
    if (input == null) {
      return null;
    }

    int start = input.indexOf(startMarker);
    if (start < 0) {
      return null;
    }

    start += startMarker.length();
    int end = input.indexOf(endMarker, start);

    return end < start ? null : input.substring(start, end).trim();
  }

  private static String extractAddress(String input) {
    if (input == null) {
      return null;
    }

    int tagStart = input.indexOf("<address");
    if (tagStart < 0) {
      return null;
    }

    int tagEnd = input.indexOf('>', tagStart);
    if (tagEnd < 0) {
      return null;
    }

    int contentStart = tagEnd + 1;
    if (contentStart < input.length() && input.charAt(contentStart) == '\n') {
      contentStart++;
    }

    int contentEnd = input.indexOf("</address>", contentStart);
    if (contentEnd < 0) {
      return null;
    }

    String address = input.substring(contentStart, contentEnd);
    address = removeHiddenSpans(address);
    address = removeSpanTags(address);

    return LookupUtils.fromHtml(address);
  }

  private static String extractWebsite(String input) {
    if (input == null) {
      return null;
    }

    String marker = "class=\"url\">";
    int searchFrom = input.length();

    while (searchFrom > 0) {
      int classIndex = input.lastIndexOf(marker, searchFrom - 1);
      if (classIndex < 0) {
        return null;
      }

      int anchorStart = input.indexOf("<a", classIndex + marker.length());
      if (anchorStart >= 0) {
        int hrefStart = input.indexOf("href=\"", anchorStart);
        if (hrefStart >= 0) {
          hrefStart += "href=\"".length();
          int hrefEnd = input.indexOf('"', hrefStart);
          if (hrefEnd > hrefStart) {
            return input.substring(hrefStart, hrefEnd).trim();
          }
        }
      }

      searchFrom = classIndex;
    }

    return null;
  }

  // Remove hidden spans, including their contents, without a regular expression.
  private static String removeHiddenSpans(String input) {
    StringBuilder result = new StringBuilder(input.length());
    int position = 0;

    while (position < input.length()) {
      int start = input.indexOf("<span class=\"hide\"", position);
      if (start < 0) {
        break;
      }

      int end = input.indexOf("</span>", start);
      if (end < 0) {
        break;
      }

      result.append(input, position, start);
      position = end + "</span>".length();
    }

    result.append(input, position, input.length());
    return result.toString();
  }

  // Remove span tags while retaining their contents.
  private static String removeSpanTags(String input) {
    StringBuilder result = new StringBuilder(input.length());
    int position = 0;

    while (position < input.length()) {
      int openingTag = input.indexOf("<span", position);
      int closingTag = input.indexOf("</span", position);

      int tagStart;
      if (openingTag < 0) {
        tagStart = closingTag;
      } else if (closingTag < 0) {
        tagStart = openingTag;
      } else {
        tagStart = Math.min(openingTag, closingTag);
      }

      if (tagStart < 0) {
        break;
      }

      int tagEnd = input.indexOf('>', tagStart);
      if (tagEnd < 0) {
        break;
      }

      result.append(input, position, tagStart);
      position = tagEnd + 1;
    }

    result.append(input, position, input.length());
    return result.toString();
  }

  public static class ContactInfo {
    String name;
    String address;
    String formattedNumber;
    String website;
  }
}
