/*
 * Copyright (C) 2006 The Android Open Source Project
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

/*
 * The source code is obtained from the Android SDK Sources (API level 31),
 * and modified by UNIDY2002 <UNIDY2002@outlook.com>.
 *
 * Change list:
 * - Remove all unused class members except guessFileName,
 *   CONTENT_DISPOSITION_PATTERN and parseContentDisposition
 * - Improve CONTENT_DISPOSITION_PATTERN and parseContentDisposition to add
 *   support for the "filename*" parameter in content disposition
 * - Replace CONTENT_DISPOSITION_PATTERN with a parameter parser that does not
 *   depend on the disposition type, the order of parameters or extra
 *   parameters, and decode RFC 2047 encoded words and percent-encoded
 *   UTF-8 / CP949 file names
 * - Keep the extension of a file name taken from the URL when the mime type
 *   does not tell the file format (e.g. application/octet-stream), add an
 *   extension from the mime type only to a file name without one, and stop
 *   cutting a file name at its first dot
 */

package com.reactnativecommunity.webview;

import android.net.Uri;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class URLUtil {
    /**
     * Guesses canonical filename that a download would have, using
     * the URL and contentDisposition. File extension, if not defined,
     * is added based on the mimetype
     * @param url Url to the content
     * @param contentDisposition Content-Disposition HTTP header or {@code null}
     * @param mimeType Mime-type of the content or {@code null}
     *
     * @return suggested filename
     */
    public static final String guessFileName(
            String url,
            @Nullable String contentDisposition,
            @Nullable String mimeType) {
        String filename = null;
        boolean isFilenameFromUrl = false;

        // If we couldn't do anything with the hint, move toward the content disposition
        if (filename == null && contentDisposition != null) {
            filename = parseContentDisposition(contentDisposition);
            if (filename != null) {
                int index = filename.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = filename.substring(index);
                }
                filename = filename.trim();
                if (filename.isEmpty()) {
                    filename = null;
                }
            }
        }

        // If all the other http-related approaches failed, use the plain uri
        if (filename == null) {
            String decodedUrl = Uri.decode(url);
            if (decodedUrl != null) {
                int queryIndex = decodedUrl.indexOf('?');
                // If there is a query string strip it, same as desktop browsers
                if (queryIndex > 0) {
                    decodedUrl = decodedUrl.substring(0, queryIndex);
                }
                if (!decodedUrl.endsWith("/")) {
                    int index = decodedUrl.lastIndexOf('/') + 1;
                    if (index > 0) {
                        filename = decodedUrl.substring(index);
                        isFilenameFromUrl = true;
                    }
                }
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename
        if (filename == null) {
            filename = "downloadfile";
        }

        String normalizedMimeType = normalizeMimeType(mimeType);
        String mimeTypeExtension = getExtensionFromMimeType(normalizedMimeType);
        String extension = getExtension(filename);

        // Add an extension if filename does not have one
        if (extension == null) {
            return mimeTypeExtension == null ? filename : filename + mimeTypeExtension;
        }

        // URL에서 얻은 이름(view.html 등)은 실제 형식과 다를 수 있어, 형식이 분명한 mime type과
        // 다르면 확장자를 바꾼다. 서버가 정해 준 이름이나 octet-stream 응답이면 확장자를 그대로 둔다
        if (isFilenameFromUrl && mimeTypeExtension != null) {
            String typeFromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    extension.substring(1).toLowerCase(Locale.ROOT));
            if (typeFromExtension != null && !typeFromExtension.equalsIgnoreCase(normalizedMimeType)) {
                return filename.substring(0, filename.length() - extension.length()) + mimeTypeExtension;
            }
        }

        return filename;
    }

    private static final Pattern EXTENSION_PATTERN = Pattern.compile("\\.[0-9a-zA-Z]{1,10}$");

    // 형식을 알려주지 않는 mime type. 여기에 맞춰 확장자를 정하면 MimeTypeMap이 .bin을 돌려준다
    private static final Set<String> GENERIC_MIME_TYPES = new HashSet<>(Arrays.asList(
            "application/octet-stream",
            "binary/octet-stream",
            "application/binary",
            "application/unknown",
            "application/x-unknown",
            "application/download",
            "application/x-download",
            "application/force-download"));

    // MimeTypeMap에 없는 한글 문서 mime type
    private static final Map<String, String> EXTENSION_BY_MIME_TYPE = new HashMap<>();

    static {
        EXTENSION_BY_MIME_TYPE.put("application/haansofthwp", "hwp");
        EXTENSION_BY_MIME_TYPE.put("application/vnd.hancom.hwp", "hwp");
        EXTENSION_BY_MIME_TYPE.put("application/haansofthwpx", "hwpx");
        EXTENSION_BY_MIME_TYPE.put("application/vnd.hancom.hwpx", "hwpx");
    }

    @Nullable
    private static String normalizeMimeType(@Nullable String mimeType) {
        if (mimeType == null) {
            return null;
        }

        int parameterIndex = mimeType.indexOf(';');
        String normalized = (parameterIndex < 0 ? mimeType : mimeType.substring(0, parameterIndex))
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /** ".hwp"처럼 점을 붙여 돌려주고, 형식을 알 수 없는 mime type이면 null */
    @Nullable
    private static String getExtensionFromMimeType(@Nullable String mimeType) {
        if (mimeType == null || GENERIC_MIME_TYPES.contains(mimeType)) {
            return null;
        }

        String extension = EXTENSION_BY_MIME_TYPE.get(mimeType);
        if (extension == null) {
            extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        }
        if (extension == null && mimeType.startsWith("text/")) {
            extension = "txt";
        }
        return extension == null ? null : "." + extension;
    }

    /** 이름 끝의 확장자(".hwp" 등). "붙임1. 설문지"처럼 확장자로 보이지 않으면 null */
    @Nullable
    private static String getExtension(String filename) {
        Matcher matcher = EXTENSION_PATTERN.matcher(filename);
        return matcher.find() ? matcher.group() : null;
    }

    /** Regex used to find the parameters of content-disposition headers */
    private static final Pattern PARAMETER_PATTERN =
            Pattern.compile("([^\\s;=]+)\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\"?|[^;]*)");

    /** RFC 5987: charset'language'percent-encoded value */
    private static final Pattern EXTENDED_VALUE_PATTERN = Pattern.compile("([^']*)'[^']*'(.*)");

    /** RFC 2047: =?charset?B|Q?encoded text?= */
    private static final Pattern ENCODED_WORD_PATTERN =
            Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");

    private static final Pattern PERCENT_ENCODED_PATTERN = Pattern.compile("%[0-9a-fA-F]{2}");

    private static final String[] FALLBACK_CHARSETS = {"UTF-8", "MS949", "EUC-KR"};

    /**
     * Parse the Content-Disposition HTTP Header. The format of the header
     * is defined here: <a href="https://www.rfc-editor.org/rfc/rfc6266">RFC 6266</a>
     * This header provides a filename for content that is going to be
     * downloaded to the file system.
     * filename*을 filename보다 먼저 쓰고, disposition 종류(attachment·inline)와
     * 파라미터 순서·개수는 서버마다 달라서 보지 않는다.
     */
    @Nullable
    static String parseContentDisposition(String contentDisposition) {
        try {
            Map<String, String> parameters = parseParameters(contentDisposition);

            String extendedFilename = parameters.get("filename*");
            if (extendedFilename != null) {
                String filename = decodeExtendedValue(extendedFilename);
                if (!filename.trim().isEmpty()) {
                    return filename;
                }
            }

            String filename = parameters.get("filename");
            if (filename != null) {
                return decodeFilenameValue(filename);
            }
        } catch (RuntimeException ex) {
            // 헤더를 읽지 못하면 null을 돌려 URL에서 파일명을 찾게 한다
        }
        return null;
    }

    private static Map<String, String> parseParameters(String header) {
        Map<String, String> parameters = new HashMap<>();
        Matcher matcher = PARAMETER_PATTERN.matcher(header);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!parameters.containsKey(name)) {
                parameters.put(name, unquote(matcher.group(2).trim()));
            }
        }
        return parameters;
    }

    private static String unquote(String value) {
        if (!value.startsWith("\"")) {
            return value;
        }

        String quoted = value.length() > 1 && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value.substring(1);
        return quoted.replaceAll("\\\\([\"\\\\])", "$1");
    }

    private static String decodeExtendedValue(String value) {
        Matcher matcher = EXTENDED_VALUE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return decodeFilenameValue(value);
        }

        byte[] bytes = percentDecodeToBytes(matcher.group(2));
        String decoded = decodeBytes(bytes, matcher.group(1));
        if (decoded == null) {
            decoded = decodeBytesWithFallback(bytes);
        }
        return decoded != null ? decoded : matcher.group(2);
    }

    private static String decodeFilenameValue(String value) {
        if (ENCODED_WORD_PATTERN.matcher(value).find()) {
            return decodeEncodedWords(value);
        }

        if (PERCENT_ENCODED_PATTERN.matcher(value).find()) {
            String decoded = decodeBytesWithFallback(percentDecodeToBytes(value));
            return decoded != null ? decoded : value;
        }

        return value;
    }

    private static String decodeEncodedWords(String value) {
        // 이어진 encoded-word 사이의 공백은 무시한다 (RFC 2047)
        String joined = value.replaceAll("\\?=\\s+=\\?", "?==?");
        Matcher matcher = ENCODED_WORD_PATTERN.matcher(joined);
        StringBuilder decoded = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            decoded.append(joined, lastEnd, matcher.start());
            String word = decodeEncodedWord(matcher.group(1), matcher.group(2), matcher.group(3));
            decoded.append(word != null ? word : matcher.group());
            lastEnd = matcher.end();
        }
        return decoded.append(joined.substring(lastEnd)).toString();
    }

    @Nullable
    private static String decodeEncodedWord(String charset, String encoding, String text) {
        try {
            byte[] bytes = "B".equalsIgnoreCase(encoding)
                    ? Base64.decode(text, Base64.DEFAULT)
                    : percentDecodeToBytes(text.replace('_', ' ').replaceAll("=([0-9a-fA-F]{2})", "%$1"));
            return decodeBytes(bytes, charset);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static byte[] percentDecodeToBytes(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Matcher matcher = PERCENT_ENCODED_PATTERN.matcher(value);
        int lastEnd = 0;
        while (matcher.find()) {
            byte[] plain = value.substring(lastEnd, matcher.start()).getBytes(StandardCharsets.UTF_8);
            bytes.write(plain, 0, plain.length);
            bytes.write(Integer.parseInt(matcher.group().substring(1), 16));
            lastEnd = matcher.end();
        }
        byte[] plain = value.substring(lastEnd).getBytes(StandardCharsets.UTF_8);
        bytes.write(plain, 0, plain.length);
        return bytes.toByteArray();
    }

    /** UTF-8, CP949 순으로 읽어 보고, 어느 쪽으로도 읽히지 않으면 null */
    @Nullable
    private static String decodeBytesWithFallback(byte[] bytes) {
        for (String charset : FALLBACK_CHARSETS) {
            String decoded = decodeBytes(bytes, charset);
            if (decoded != null) {
                return decoded;
            }
        }
        return null;
    }

    /** 지원하지 않는 charset이거나 그 charset으로 읽히지 않는 바이트면 null */
    @Nullable
    private static String decodeBytes(byte[] bytes, String charset) {
        try {
            return Charset.forName(charset.trim())
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException | IllegalArgumentException ex) {
            return null;
        }
    }
}
