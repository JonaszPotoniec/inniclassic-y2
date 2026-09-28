package com.themoon.y1.managers;

import android.content.Context;
import com.themoon.y1.StoragePaths;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class LanguageManager {
    private static LanguageManager instance;
    private Context context;

    // 💡 번역된 단어들이 저장될 메모리 단어장
    private final HashMap<String, String> dictionary = new HashMap<>();

    public List<File> availableLangFiles = new ArrayList<>();
    public String currentLangFileName = "English (Default)";

    private LanguageManager(Context context) {
        this.context = context.getApplicationContext();
        loadAvailableLanguages();
    }

    public static synchronized LanguageManager getInstance(Context context) {
        if (instance == null) instance = new LanguageManager(context);
        return instance;
    }

    // 1. 폴더 및 assets에서 .json 언어팩 파일들을 스캔합니다.
    public void loadAvailableLanguages() {
        availableLangFiles.clear();
        File langDir = StoragePaths.getLanguagesDir();
        if (!langDir.exists()) langDir.mkdirs();

        File[] files = langDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().toLowerCase().endsWith(".json")) {
                    availableLangFiles.add(f);
                }
            }
        }

        // assets/languages 폴더의 기본 언어팩도 누락되지 않도록 목록에 확보
        if (context != null) {
            try {
                String[] assetLangs = context.getAssets().list("languages");
                if (assetLangs != null) {
                    for (String name : assetLangs) {
                        if (name.toLowerCase().endsWith(".json")) {
                            boolean alreadyAdded = false;
                            for (File existing : availableLangFiles) {
                                if (existing.getName().equalsIgnoreCase(name)) {
                                    alreadyAdded = true;
                                    break;
                                }
                            }
                            if (!alreadyAdded) {
                                availableLangFiles.add(new File(langDir, name));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // 2. 선택된 언어팩 JSON 파일을 읽어와 단어장에 등록합니다.
    public void applyLanguage(String fileName) {
        dictionary.clear();
        currentLangFileName = fileName != null ? fileName : "English (Default)";

        if ("English (Default)".equalsIgnoreCase(currentLangFileName)) return; // 기본값일 경우 빈 단어장 유지 (원본 출력)

        InputStream is = null;
        try {
            // 1순위: 저장소의 Y1_Languages 폴더에서 파일 읽기
            File f = new File(StoragePaths.getLanguagesDir(), currentLangFileName);
            if (f.exists() && f.length() > 0) {
                is = new FileInputStream(f);
            } else if (context != null) {
                // 2순위: 저장소에 파일이 없거나 마운트 전인 경우 assets에서 직접 읽기
                is = context.getAssets().open("languages/" + currentLangFileName);
            }

            if (is != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, count);
                }
                is.close();

                String jsonStr = new String(baos.toByteArray(), "UTF-8");
                JSONObject json = new JSONObject(jsonStr);

                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String originalText = keys.next();
                    String translatedText = json.getString(originalText);
                    dictionary.put(originalText, translatedText);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 혹시 대소문자 문제일 경우 소문자로 재시도
            try {
                if (context != null && !currentLangFileName.equals(currentLangFileName.toLowerCase())) {
                    InputStream fallbackIs = context.getAssets().open("languages/" + currentLangFileName.toLowerCase());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = fallbackIs.read(buffer)) != -1) {
                        baos.write(buffer, 0, count);
                    }
                    fallbackIs.close();
                    String jsonStr = new String(baos.toByteArray(), "UTF-8");
                    JSONObject json = new JSONObject(jsonStr);
                    java.util.Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String originalText = keys.next();
                        dictionary.put(originalText, json.getString(originalText));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // 🚀 [초고속 단어장 검색 엔진]
    public String t(String originalText) {
        if (originalText == null) return "";
        if (dictionary.isEmpty()) return originalText;

        String translated = dictionary.get(originalText);
        if (translated != null) {
            return translated;
        }

        // 만약 완벽히 일치하지 않는다면 양쪽 공백을 제거하고 다시 한 번 검색
        String trimmed = originalText.trim();
        translated = dictionary.get(trimmed);
        if (translated != null) {
            // 원본의 앞뒤 공백이나 이모지 형태를 유지하기 위해 살짝 가공
            return originalText.replace(trimmed, translated);
        }

        // 번역팩에 해당 단어가 없으면 그냥 원래 영어 단어를 그대로 내보냅니다.
        return originalText;
    }
}