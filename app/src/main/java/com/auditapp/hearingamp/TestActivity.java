package com.auditapp.hearingamp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;
import android.widget.Toast;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TestActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "TestActivityPrefs";
    private static final String PROTOCOLS_KEY = "Protocols";

    private TextView txtEarOrder, txtTestSequenceLabel, txtTestSequence;
    private Button btnLEarOnly, btnREarOnly, btnLEarToREar, btnREarToLEar;
    private Button btnReturnToTitle, btnRemoveLast, btnLoadProtocol, btnSaveProtocol;
    private Button btnMCLTest, btnThresholdTest, btnDeleteCurrentProtocol, btnClearAll;
    private Button btnAddFrequency;
    private ArrayList<String> testSequence;
    private String earOrder;
    private Map<String, ArrayList<String>> protocols;
    private String lastSavedProtocol;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initViews();
        setupClickListeners();
        updateTexts();

        protocols = loadProtocols();
        earOrder = prefs.getString("EarOrder", getString(R.string.none)); // Set default value if null
        lastSavedProtocol = null;

        // Load test sequence from SharedPreferences
        Gson gson = new Gson();
        String json = prefs.getString("TestSequence", "");
        if (!json.isEmpty()) {
            testSequence = gson.fromJson(json, ArrayList.class);
        }
        if (testSequence == null) {
            testSequence = new ArrayList<>();
        }

        // Update UI based on loaded data
        updateTestSequence();
        updateEarOrder(earOrder);

        // Update buttons enabled state based on conditions
        updateTestButtonsState();
    }

    private void initViews() {
        txtEarOrder = findViewById(R.id.txtEarOrder);
        txtTestSequenceLabel = findViewById(R.id.txtTestSequenceLabel);
        txtTestSequence = findViewById(R.id.txtTestSequence);
        btnLEarOnly = findViewById(R.id.button2);
        btnREarOnly = findViewById(R.id.button3);
        btnLEarToREar = findViewById(R.id.button4);
        btnREarToLEar = findViewById(R.id.button5);
        btnReturnToTitle = findViewById(R.id.returnToTitleButton);
        btnRemoveLast = findViewById(R.id.removeLastbutton);
        btnLoadProtocol = findViewById(R.id.loadProtocolbutton);
        btnSaveProtocol = findViewById(R.id.saveProtocolbutton);
        btnMCLTest = findViewById(R.id.MCLTestButton);
        btnThresholdTest = findViewById(R.id.ThresholdTestButton);
        btnDeleteCurrentProtocol = findViewById(R.id.deletecurrentProtocolButton);
        btnClearAll = findViewById(R.id.clearAllbutton);
        btnAddFrequency = findViewById(R.id.addFrequencyButton);

        testSequence = new ArrayList<>();
    }

    private void setupClickListeners() {
        btnReturnToTitle.setOnClickListener(view -> finish());

        btnRemoveLast.setOnClickListener(view -> {
            if (!testSequence.isEmpty()) {
                testSequence.remove(testSequence.size() - 1);
                updateTestSequence();
            }
        });

        btnClearAll.setOnClickListener(view -> {
            testSequence.clear();
            updateTestSequence();
        });

        btnSaveProtocol.setOnClickListener(view -> {
            if (testSequence.isEmpty()) {
                Toast.makeText(this, R.string.no_test_sequence, Toast.LENGTH_SHORT).show();
                return;
            }
            showSaveProtocolDialog();
        });

        btnLoadProtocol.setOnClickListener(view -> showLoadProtocolDialog());

        btnDeleteCurrentProtocol.setOnClickListener(view -> {
            if (lastSavedProtocol != null) {
                protocols.remove(lastSavedProtocol);
                saveProtocols();
                lastSavedProtocol = null;
                Toast.makeText(this, R.string.protocol_deleted, Toast.LENGTH_SHORT).show();
                testSequence.clear();
                updateTestSequence();
            } else {
                Toast.makeText(this, R.string.no_protocols_chosen, Toast.LENGTH_SHORT).show();
            }
        });

        btnAddFrequency.setOnClickListener(view -> showFrequencyMenu());

        btnLEarOnly.setOnClickListener(view -> updateEarOrder(getString(R.string.lear_only)));
        btnREarOnly.setOnClickListener(view -> updateEarOrder(getString(R.string.rear_only)));
        btnLEarToREar.setOnClickListener(view -> updateEarOrder(getString(R.string.lear_to_rear)));
        btnREarToLEar.setOnClickListener(view -> updateEarOrder(getString(R.string.rear_to_lear)));

        btnThresholdTest.setOnClickListener(view -> {
            if (canStartTest()) {
                showPatientInfoDialog();
            } else {
                Toast.makeText(this, R.string.ear_order_test_sequence_required, Toast.LENGTH_SHORT).show();
            }
        });

        btnMCLTest.setOnClickListener(view -> {
            if (canStartTest()) {
                showMCLPatientInfoDialog();
            } else {
                Toast.makeText(this, R.string.ear_order_test_sequence_required, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean canStartTest() {
        return earOrder != null && !earOrder.equals(getString(R.string.none)) && !testSequence.isEmpty();
    }

    private void updateTestButtonsState() {
        boolean canStartTest = canStartTest();
        btnThresholdTest.setEnabled(canStartTest);
        btnMCLTest.setEnabled(canStartTest);
    }

    private void updateTexts() {
        txtTestSequenceLabel.setText(getString(R.string.test_sequence));
        btnLEarOnly.setText(getString(R.string.lear_only));
        btnREarOnly.setText(getString(R.string.rear_only));
        btnLEarToREar.setText(getString(R.string.lear_to_rear));
        btnREarToLEar.setText(getString(R.string.rear_to_lear));
        btnReturnToTitle.setText(getString(R.string.return_to_title));
        btnRemoveLast.setText(getString(R.string.remove_last));
        btnLoadProtocol.setText(getString(R.string.load_protocol));
        btnSaveProtocol.setText(getString(R.string.save_protocol));
        btnMCLTest.setText(getString(R.string.MCL_test));
        btnThresholdTest.setText(getString(R.string.Threshold_test));
        btnDeleteCurrentProtocol.setText(getString(R.string.delete_current_protocol));
        btnClearAll.setText(getString(R.string.clear_all));
        btnAddFrequency.setText(getString(R.string.add_frequency));
        updateTestSequence();
        updateEarOrder(earOrder);
    }

    private void updateTestSequence() {
        if (testSequence.isEmpty()) {
            txtTestSequence.setText(getString(R.string.none));
        } else {
            StringBuilder sequence = new StringBuilder();
            for (String freq : testSequence) {
                sequence.append(freq).append("-> ");
            }
            sequence.delete(sequence.length() - 3, sequence.length());  // Remove the last "-> "
            txtTestSequence.setText(sequence.toString());
        }

        // Save test sequence to SharedPreferences as a JSON string
        Gson gson = new Gson();
        String json = gson.toJson(testSequence);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("TestSequence", json);
        editor.apply();

        updateTestButtonsState();
    }

    private void updateEarOrder(String order) {
        earOrder = order != null ? order : getString(R.string.none);
        txtEarOrder.setText(getString(R.string.ear_order) + " " + earOrder);
        prefs.edit().putString("EarOrder", earOrder).apply();
        Log.d("TestActivity", "EarOrder updated: " + earOrder);

        updateTestButtonsState();
    }

    private void showSaveProtocolDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.save_protocol);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            String protocolName = input.getText().toString().trim();
            if (!protocolName.isEmpty()) {
                protocols.put(protocolName, new ArrayList<>(testSequence));
                lastSavedProtocol = protocolName;
                saveProtocols();
                Toast.makeText(this, getString(R.string.protocol_saved, protocolName), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.protocol_name_empty, Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void showLoadProtocolDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.load_protocol);

        final Set<String> protocolNames = protocols.keySet();
        if (protocolNames.isEmpty()) {
            Toast.makeText(this, R.string.no_saved_protocols, Toast.LENGTH_SHORT).show();
            return;
        }

        // Sort protocols by the most recent one saved
        ArrayList<String> sortedProtocolNames = new ArrayList<>(protocolNames);
        Collections.sort(sortedProtocolNames, (a, b) -> b.compareTo(a));

        final String[] protocolArray = sortedProtocolNames.toArray(new String[0]);
        builder.setItems(protocolArray, (dialog, which) -> {
            String selectedProtocol = protocolArray[which];
            testSequence = new ArrayList<>(protocols.get(selectedProtocol));
            lastSavedProtocol = selectedProtocol;
            updateTestSequence();
        });

        builder.show();
    }

    private void showFrequencyMenu() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.add_frequency);

        final String[] frequencies = {"250 Hz", "500 Hz", "750 Hz", "1000 Hz", "1500 Hz", "2000 Hz", "3000 Hz", "4000 Hz", "6000 Hz", "8000 Hz"};
        builder.setItems(frequencies, (dialog, which) -> {
            String selectedFrequency = frequencies[which];
            if (!testSequence.contains(selectedFrequency)) {
                testSequence.add(selectedFrequency);
                updateTestSequence();
            } else {
                Toast.makeText(this, R.string.frequency_already_added, Toast.LENGTH_SHORT).show();
            }
        });

        builder.show();
    }

    private Map<String, ArrayList<String>> loadProtocols() {
        Map<String, ArrayList<String>> protocols = new HashMap<>();
        Set<String> protocolNames = prefs.getStringSet(PROTOCOLS_KEY, new HashSet<>());
        for (String protocolName : protocolNames) {
            Set<String> protocolSet = prefs.getStringSet(PROTOCOLS_KEY + "_" + protocolName, new HashSet<>());
            protocols.put(protocolName, new ArrayList<>(protocolSet));
        }
        return protocols;
    }

    private void saveProtocols() {
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> protocolNames = protocols.keySet();
        editor.putStringSet(PROTOCOLS_KEY, protocolNames);
        for (String protocolName : protocolNames) {
            Set<String> protocolSet = new HashSet<>(protocols.get(protocolName));
            editor.putStringSet(PROTOCOLS_KEY + "_" + protocolName, protocolSet);
        }
        editor.apply();
    }

    private void showPatientInfoDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.enter_patient_info);

        final EditText groupInput = new EditText(this);
        groupInput.setHint(R.string.patient_group);
        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.patient_name);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(groupInput);
        layout.addView(nameInput);
        builder.setView(layout);

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            String patientGroup = groupInput.getText().toString().trim();
            String patientName = nameInput.getText().toString().trim();
            if (!patientGroup.isEmpty() && !patientName.isEmpty()) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("patientGroup", patientGroup);
                editor.putString("patientName", patientName);
                editor.putString("EarOrder", earOrder);
                editor.apply();

                Log.d("TestActivity", "EarOrder before starting TestInstructionActivity: " + earOrder);

                Intent intent = new Intent(TestActivity.this, TestThresholdInstructionActivity.class);
                intent.putExtra("patientGroup", patientGroup);
                intent.putExtra("patientName", patientName);
                intent.putExtra("EarOrder", earOrder);
                startActivity(intent);
            } else {
                Toast.makeText(this, R.string.patient_info_empty, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void showMCLPatientInfoDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.enter_patient_info);

        final EditText groupInput = new EditText(this);
        groupInput.setHint(R.string.patient_group);
        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.patient_name);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(groupInput);
        layout.addView(nameInput);
        builder.setView(layout);

        builder.setPositiveButton(R.string.confirm, (dialog, which) -> {
            String patientGroup = groupInput.getText().toString().trim();
            String patientName = nameInput.getText().toString().trim();
            if (!patientGroup.isEmpty() && !patientName.isEmpty()) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("patientGroup", patientGroup);
                editor.putString("patientName", patientName);
                editor.putString("EarOrder", earOrder);
                editor.apply();

                Log.d("TestActivity", "EarOrder before starting TestMCLInstructionActivity: " + earOrder);

                Intent intent = new Intent(TestActivity.this, TestMCLInstructionActivity.class);
                intent.putExtra("patientGroup", patientGroup);
                intent.putExtra("patientName", patientName);
                intent.putExtra("EarOrder", earOrder);
                startActivity(intent);
            } else {
                Toast.makeText(this, R.string.patient_info_empty, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }
}