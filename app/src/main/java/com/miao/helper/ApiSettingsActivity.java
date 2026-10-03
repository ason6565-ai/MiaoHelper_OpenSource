package com.miao.helper;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** API 设置单独页面 */
public class ApiSettingsActivity extends AppCompatActivity {
    private Spinner spProvider;
    private EditText etBaseUrl, etModel, etKey, etUpdateUrl, etModelLong;
    private SwitchMaterial swAiVerify;
    private SwitchMaterial swGuardPreview;
    private MaterialButton btnEditKey, btnTest, btnClearCache, btnFetchModels, btnCheckUpdate;
    private Spinner spCacheTtl, spLogLevel;
    private MaterialButton btnManageEngines;
    private TextView tvBackupCount;
    private static final long[] TTL_VALUES = {3600_000L, 6*3600_000L, 12*3600_000L, 24*3600_000L, 7*24*3600_000L, Long.MAX_VALUE};
    // TTL/日志级别选项已资源化（R.array.ttl_labels / log_level_labels）

    private TextView tvCacheStats;
    private String prevKey = "";
    private String latestEditedKey = null;   // 编辑对话框里正在输入的最新 Key（未保存也算），测试连接优先用它
    private boolean providerSyncing = false;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRunnable = new Runnable() {
        @Override public void run() {
            updateCacheStats();
            statsHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        spProvider = findViewById(R.id.spProvider);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etModel = findViewById(R.id.etModel);
        etModelLong = findViewById(R.id.etModelLong);
        etKey = findViewById(R.id.etKey);
        btnEditKey = findViewById(R.id.btnEditKey);
        btnTest = findViewById(R.id.btnTest);
        btnClearCache = findViewById(R.id.btnClearCache);
        btnFetchModels = findViewById(R.id.btnFetchModels);
        tvCacheStats = findViewById(R.id.tvCacheStats);

        // 5.0 高级设置：缓存TTL / 日志级别 / 备用引擎
        spCacheTtl = findViewById(R.id.spCacheTtl);
        spLogLevel = findViewById(R.id.spLogLevel);
        btnManageEngines = findViewById(R.id.btnManageEngines);
        tvBackupCount = findViewById(R.id.tvBackupCount);

        initCacheTtlSpinner();
        initLogLevelSpinner();
        initBackupEngines();

        // AI 二次校验开关
        swAiVerify = findViewById(R.id.swAiVerify);
        swAiVerify.setChecked(Prefs.aiVerify());
        swAiVerify.setOnCheckedChangeListener((v, on) -> Prefs.set("aiVerify", on));
        swGuardPreview = findViewById(R.id.swGuardPreview);
        swGuardPreview.setChecked(Prefs.forceGuard());
        swGuardPreview.setOnCheckedChangeListener((v, on) -> Prefs.set("forceGuard", on));

        // 厂商下拉
        String[] providerNames = new String[Prefs.PROVIDERS.length];
        for (int i = 0; i < Prefs.PROVIDERS.length; i++) providerNames[i] = Prefs.PROVIDERS[i][0];
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, providerNames);
        spProvider.setAdapter(providerAdapter);

        // 载入配置
        providerSyncing = true;
        spProvider.setSelection(Math.min(Prefs.apiProvider(), Prefs.PROVIDERS.length - 1));
        providerSyncing = false;
        etBaseUrl.post(() -> {
            etBaseUrl.setText(Prefs.apiBaseUrl());
            etModel.setText(Prefs.apiModel());
            etModelLong.setText(Prefs.apiModelLong());
        });
        prevKey = Prefs.apiKey();
        etKey.setText(maskKey(prevKey));

        // 厂商切换
        spProvider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                if (providerSyncing) return;
                Prefs.set("apiProvider", pos);
                if (pos < Prefs.PROVIDERS.length - 1) {
                    final String base = Prefs.PROVIDERS[pos][1];
                    String model = Prefs.PROVIDERS[pos][2];
                    // 厂商切换不再无条件覆盖模型：当前模型若是用户自定义（不属于任何厂商预设）则保留，
                    // 否则（仍为预设）才跟随切到新厂商预设模型，避免用户所选模型被强制重置
                    String cur = Prefs.apiModel();
                    boolean preset = false;
                    for (String[] prov : Prefs.PROVIDERS) {
                        if (prov[2].equals(cur)) { preset = true; break; }
                    }
                    if (!preset && cur != null && !cur.trim().isEmpty()) model = cur.trim();
                    final String fModel = model;
                    etBaseUrl.clearFocus();
                    etModel.clearFocus();
                    Prefs.set("apiBaseUrl", base);
                    Prefs.set("apiModel", fModel);
                    etBaseUrl.post(() -> {
                        etBaseUrl.setText(base);
                        etModel.setText(fModel);
                    });
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // 地址/模型失焦保存
        etBaseUrl.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                String raw = etBaseUrl.getText().toString().trim();
                if (raw.isEmpty()) return;
                // 强制 HTTPS：http 自动升级、缺协议头自动补全，避免 Key 走明文（老设备兜底）
                String cur = ApiMiaoifier.secureBase(raw, "https://api.deepseek.com");
                if (!cur.equals(raw)) {
                    etBaseUrl.setText(cur);
                    Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_force_https_fmt, cur), Toast.LENGTH_SHORT).show();
                }
                if (!cur.equals(Prefs.apiBaseUrl())) Prefs.set("apiBaseUrl", cur);
            }
        });
        etModel.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                String cur = etModel.getText().toString().trim();
                if (!cur.equals(Prefs.apiModel())) Prefs.set("apiModel", cur);
            }
        });
        etModelLong.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                String cur = etModelLong.getText().toString().trim();
                if (!cur.equals(Prefs.apiModelLong())) Prefs.set("apiModelLong", cur);
            }
        });

        // 编辑 Key
        btnEditKey.setOnClickListener(v -> {
            final EditText input = new EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setText(Prefs.apiKey());
            input.setSelection(input.getText().length());
            latestEditedKey = null;   // 每次打开对话框重新记录，避免沿用上次未保存的输入
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    latestEditedKey = s.toString().trim();
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.api_edit_key))
                    .setView(input)
                    .setPositiveButton(getString(R.string.save), (d, w) -> {
                        String k = input.getText().toString().trim();
                        Prefs.set("apiKey", k);
                        prevKey = k;
                        etKey.setText(maskKey(k));
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        });

        // 测试连接
        btnTest.setOnClickListener(v -> {
            // 优先用对话框里正在输入的最新 Key（未保存也能测），否则用已保存的
            String key = (latestEditedKey != null && !latestEditedKey.isEmpty())
                    ? latestEditedKey : Prefs.apiKey().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, getString(R.string.api_key_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            btnTest.setEnabled(false);
            btnTest.setText(getString(R.string.api_testing));
            ApiMiaoifier.testConnection(key, etBaseUrl.getText().toString().trim(),
                    etModel.getText().toString().trim(), new ApiMiaoifier.Callback() {
                @Override public void onSuccess(String text) {
                    runOnUiThread(() -> {
                        btnTest.setEnabled(true);
                        btnTest.setText(getString(R.string.api_test_conn));
                        Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_test_ok_fmt, text), Toast.LENGTH_LONG).show();
                    });
                }
                @Override public void onError(String msg) {
                    runOnUiThread(() -> {
                        btnTest.setEnabled(true);
                        btnTest.setText(getString(R.string.api_test_conn));
                        Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_test_fail_fmt, msg), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        // 刷新可用模型：实时拉取该 Key 在当前厂商下可用的模型 ID，点选填入，避免写死过期或手输错
        btnFetchModels.setOnClickListener(v -> {
            String key = (latestEditedKey != null && !latestEditedKey.isEmpty())
                    ? latestEditedKey : Prefs.apiKey().trim();
            String base = etBaseUrl.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, getString(R.string.api_key_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            btnFetchModels.setEnabled(false);
            btnFetchModels.setText(getString(R.string.api_fetching_models));
            Prefs.set("apiModel", etModel.getText().toString().trim());
            Prefs.set("apiModelLong", etModelLong.getText().toString().trim());
            ApiMiaoifier.listModels(key, base, new ApiMiaoifier.ModelListCallback() {
                @Override public void onSuccess(final List<String> models) {
                    runOnUiThread(() -> {
                        resetFetchBtn();
                        final String[] arr = models.toArray(new String[0]);
                        int checked = -1;
                        String cur = etModel.getText().toString().trim();
                        for (int i = 0; i < arr.length; i++) if (arr[i].equals(cur)) checked = i;
                        new AlertDialog.Builder(ApiSettingsActivity.this)
                                .setTitle(getString(R.string.api_models_title_fmt, arr.length))
                                .setSingleChoiceItems(arr, checked, (d, which) -> {
                                    String id = arr[which];
                                    etModel.setText(id);
                                    Prefs.set("apiModel", id);
                                    Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_selected_fmt, id), Toast.LENGTH_SHORT).show();
                                    d.dismiss();
                                })
                                .setNegativeButton(getString(R.string.cancel), null)
                                .show();
                    });
                }
                @Override public void onError(final String msg) {
                    runOnUiThread(() -> {
                        resetFetchBtn();
                        Toast.makeText(ApiSettingsActivity.this, msg, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        // 清除缓存
        btnClearCache.setOnClickListener(v -> {
            ApiMiaoifier.clearCache();
            updateCacheStats();
            Toast.makeText(this, getString(R.string.api_cache_cleared), Toast.LENGTH_SHORT).show();
        });

        // v3.3 应用内更新检查：地址可配置，只检查+提示+跳浏览器，不在应用内下载安装
        etUpdateUrl = findViewById(R.id.etUpdateUrl);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        etUpdateUrl.post(() -> etUpdateUrl.setText(Prefs.updateUrl()));
        etUpdateUrl.setOnFocusChangeListener((vv, has) -> {
            if (!has) Prefs.set("updateUrl", etUpdateUrl.getText().toString().trim());
        });
        btnCheckUpdate.setOnClickListener(v -> {
            Prefs.set("updateUrl", etUpdateUrl.getText().toString().trim());
            if (Prefs.updateUrl().isEmpty()) {
                Toast.makeText(this, getString(R.string.api_json_url_empty), Toast.LENGTH_SHORT).show();
                return;
            }
            btnCheckUpdate.setEnabled(false);
            btnCheckUpdate.setText(getString(R.string.api_checking));
            UpdateChecker.check(this, new UpdateChecker.Callback() {
                @Override public void onUpdate(UpdateChecker.Info info) {
                    btnCheckUpdate.setEnabled(true);
                    btnCheckUpdate.setText(getString(R.string.api_check_update));
                    String msg = getString(R.string.api_new_version_fmt, info.versionName, (info.notes == null ? "" : info.notes));
                    new AlertDialog.Builder(ApiSettingsActivity.this)
                            .setTitle(getString(R.string.api_update_available))
                            .setMessage(msg)
                            .setPositiveButton(getString(R.string.api_go_download), (d, w) -> {
                                try {
                                    android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(info.url));
                                    startActivity(it);
                                } catch (Exception ex) {
                                    Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_download_fail), Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(getString(R.string.api_later), null)
                            .show();
                }
                @Override public void onUpToDate() {
                    btnCheckUpdate.setEnabled(true);
                    btnCheckUpdate.setText(getString(R.string.api_check_update));
                    Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_latest), Toast.LENGTH_SHORT).show();
                }
                @Override public void onFail(String m) {
                    btnCheckUpdate.setEnabled(true);
                    btnCheckUpdate.setText(getString(R.string.api_check_update));
                    Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_check_fail_fmt, m), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCacheStats();
        statsHandler.postDelayed(statsRunnable, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statsHandler.removeCallbacks(statsRunnable);
        persistEdits();   // 兜底保存：返回键/切界面时 EditText 失焦回调不可靠，改完直接退出会丢
    }

    /** 兜底保存：与失焦保存逻辑一致；保证模型/地址/长文本模型改动在退出界面时落盘 */
    private void persistEdits() {
        try {
            String raw = etBaseUrl.getText().toString().trim();
            if (!raw.isEmpty()) {
                String cur = ApiMiaoifier.secureBase(raw, "https://api.deepseek.com");
                if (!cur.equals(Prefs.apiBaseUrl())) Prefs.set("apiBaseUrl", cur);
            }
            String m = etModel.getText().toString().trim();
            if (!m.isEmpty() && !m.equals(Prefs.apiModel())) Prefs.set("apiModel", m);
            String ml = etModelLong.getText().toString().trim();
            if (!ml.equals(Prefs.apiModelLong())) Prefs.set("apiModelLong", ml);
        } catch (Throwable ignored) { }
    }

    private void resetFetchBtn() {
        btnFetchModels.setEnabled(true);
        btnFetchModels.setText(getString(R.string.api_refresh_models));
    }
    private void updateCacheStats() {
        long bytes = ApiMiaoifier.getCacheBytes();
        String mb = String.format(java.util.Locale.US, "%.1f", bytes / (1024f * 1024f));
        tvCacheStats.setText(getString(R.string.api_cache_stats_fmt, ApiMiaoifier.getCacheHits(), ApiMiaoifier.getTotalRequests(), mb, (FileCacheStore.DEFAULT_MAX_BYTES / (1024 * 1024))));
    }

    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    // ==================== 5.0 高级设置 ====================

    private void initCacheTtlSpinner() {
        String[] ttlLabels = getResources().getStringArray(R.array.ttl_labels);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ttlLabels);
        spCacheTtl.setAdapter(adapter);
        long cur = Prefs.cacheTtlMs();
        int sel = ttlLabels.length - 1;
        for (int i = 0; i < TTL_VALUES.length; i++) {
            if (TTL_VALUES[i] == cur) { sel = i; break; }
        }
        spCacheTtl.setSelection(sel);
        spCacheTtl.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                Prefs.setCacheTtlMs(TTL_VALUES[pos]);
                ApiMiaoifier.refreshCacheTtl();
                Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_ttl_fmt, getResources().getStringArray(R.array.ttl_labels)[pos]), Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
    }

    private void initLogLevelSpinner() {
        String[] logLabels = getResources().getStringArray(R.array.log_level_labels);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, logLabels);
        spLogLevel.setAdapter(adapter);
        int cur = Prefs.logLevel();
        spLogLevel.setSelection(Math.max(0, Math.min(logLabels.length - 1, cur - 1)));
        spLogLevel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                int lv = pos + 1;
                Prefs.setLogLevel(lv);
                AppLog.setLevel(lv);
                Toast.makeText(ApiSettingsActivity.this, getString(R.string.api_loglevel_fmt, getResources().getStringArray(R.array.log_level_labels)[pos]), Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
    }

    private void initBackupEngines() {
        updateBackupCount();
        btnManageEngines.setOnClickListener(v -> showBackupEngineDialog());
    }

    private void updateBackupCount() {
        int n = Prefs.backupEngines().size();
        tvBackupCount.setText(getString(R.string.api_backup_count_fmt, n));
    }

    private void showBackupEngineDialog() {
        final List<Engine> engines = new ArrayList<>(Prefs.backupEngines());
        final String[] items = new String[engines.size() + 1];
        for (int i = 0; i < engines.size(); i++) {
            items[i] = engines.get(i).name + "  —  " + engines.get(i).baseUrl;
        }
        items[engines.size()] = getString(R.string.api_add_backup);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.api_backup_manage))
                .setItems(items, (d, which) -> {
                    if (which == engines.size()) {
                        showAddEngineDialog(engines, -1);
                    } else {
                        showAddEngineDialog(engines, which);
                    }
                })
                .setNegativeButton(getString(R.string.api_close), null)
                .show();
    }

    private void showAddEngineDialog(final List<Engine> engines, final int editIndex) {
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setPadding(32, 16, 32, 16);

        final EditText etName = new EditText(this);
        etName.setHint(getString(R.string.api_backup_name_hint));
        final EditText etBase = new EditText(this);
        etBase.setHint(getString(R.string.api_backup_url_hint));
        final EditText etModel = new EditText(this);
        etModel.setHint(getString(R.string.api_backup_model_hint));
        final EditText etKey = new EditText(this);
        etKey.setHint("API Key");
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        if (editIndex >= 0) {
            Engine e = engines.get(editIndex);
            etName.setText(e.name);
            etBase.setText(e.baseUrl);
            etModel.setText(e.model);
            etKey.setText(e.apiKey);
        }

        ll.addView(etName);
        ll.addView(etBase);
        ll.addView(etModel);
        ll.addView(etKey);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? getString(R.string.api_edit_backup) : getString(R.string.api_add_backup_title))
                .setView(ll)
                .setPositiveButton(getString(R.string.save), (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String base = etBase.getText().toString().trim();
                    String model = etModel.getText().toString().trim();
                    String key = etKey.getText().toString().trim();
                    if (name.isEmpty() || base.isEmpty()) {
                        Toast.makeText(this, getString(R.string.api_backup_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Engine e = new Engine(name, base, key, model);
                    if (editIndex >= 0) engines.set(editIndex, e);
                    else engines.add(e);
                    Prefs.setBackupEngines(engines);
                    updateBackupCount();
                    Toast.makeText(this, getString(R.string.api_saved_fmt, name), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.cancel), null);
        if (editIndex >= 0) {
            b.setNeutralButton(getString(R.string.api_delete), (d, w) -> {
                engines.remove(editIndex);
                Prefs.setBackupEngines(engines);
                updateBackupCount();
                Toast.makeText(this, getString(R.string.api_deleted), Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }
}
