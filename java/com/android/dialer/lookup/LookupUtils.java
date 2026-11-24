/*
 * Copyright (C) 2014 The CyanogenMod Project
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

package com.infinityx.dialer.lookup;

import android.text.Html;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Map;

public class LookupUtils {
  private static final String USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64; rv:42.0) Gecko/20100101 Firefox/42.0";

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 20_000;
  private static final int MAX_TEXT_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final int MAX_BINARY_RESPONSE_BYTES = 8 * 1024 * 1024;

  private static HttpURLConnection prepareHttpConnection(String url, Map<String, String> headers)
      throws IOException {
    // open connection
    HttpURLConnection urlConnection = (HttpURLConnection) new URL(url).openConnection();
    urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    urlConnection.setReadTimeout(READ_TIMEOUT_MS);

    // set user agent (default value is null)
    urlConnection.setRequestProperty("User-Agent", USER_AGENT);
    // set all other headers if not null
    if (headers != null) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        urlConnection.setRequestProperty(header.getKey(), header.getValue());
      }
    }

    return urlConnection;
  }

  private static byte[] httpFetch(
      HttpURLConnection urlConnection, int maxResponseBytes) throws IOException {
    int contentLength = urlConnection.getContentLength();
    if (contentLength > maxResponseBytes) {
      throw new IOException("HTTP response exceeds maximum allowed size");
    }

    try (InputStream is = new BufferedInputStream(urlConnection.getInputStream());
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] partial = new byte[4096];
      int read;
      int totalBytes = 0;

      while ((read = is.read(partial)) != -1) {
        if (read > maxResponseBytes - totalBytes) {
          throw new IOException("HTTP response exceeds maximum allowed size");
        }

        baos.write(partial, 0, read);
        totalBytes += read;
      }

      return baos.toByteArray();
    }
  }

  private static Charset determineCharset(HttpURLConnection connection) {
    String contentType = connection.getContentType();
    if (contentType != null) {
      String[] split = contentType.split(";");
      for (int i = 0; i < split.length; i++) {
        String trimmed = split[i].trim();
        if (trimmed.startsWith("charset=")) {
          try {
            return Charset.forName(trimmed.substring(8));
          } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            // we don't know about this charset -> ignore
          }
        }
      }
    }
    return Charset.defaultCharset();
  }

  public static String httpGet(String url, Map<String, String> headers) throws IOException {
    HttpURLConnection connection = prepareHttpConnection(url, headers);
    try {
      byte[] response = httpFetch(connection, MAX_TEXT_RESPONSE_BYTES);
      return new String(response, determineCharset(connection));
    } finally {
      connection.disconnect();
    }
  }

  public static byte[] httpGetBytes(String url, Map<String, String> headers) throws IOException {
    HttpURLConnection connection = prepareHttpConnection(url, headers);
    try {
      return httpFetch(connection, MAX_BINARY_RESPONSE_BYTES);
    } finally {
      connection.disconnect();
    }
  }

  public static String httpPost(String url, Map<String, String> headers, String postData)
      throws IOException {
    HttpURLConnection connection = prepareHttpConnection(url, headers);

    try {
      // write postData to buffered output stream
      if (postData != null) {
        connection.setDoOutput(true);
        BufferedWriter bw = new BufferedWriter(
            new OutputStreamWriter(connection.getOutputStream()));
        try {
          bw.write(postData, 0, postData.length());
          // close connection and re-throw exception
        } finally {
          bw.close();
        }
      }
      byte[] response = httpFetch(connection, MAX_TEXT_RESPONSE_BYTES);
      return new String(response, determineCharset(connection));
    } finally {
      connection.disconnect();
    }
  }

  public static String fromHtml(String input) {
    if (input == null) {
      return null;
    }
    return Html.fromHtml(input, Html.FROM_HTML_MODE_LEGACY).toString().trim();
  }
}
