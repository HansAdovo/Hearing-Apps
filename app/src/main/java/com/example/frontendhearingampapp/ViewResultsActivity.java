package com.example.frontendhearingampapp;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ViewResultsActivity extends AppCompatActivity {

    private Button btnExportAllPatientData, btnDeleteAllPatientProfiles, btnReturnToTitle;
    private TextView tvCurrentFrequency;
    private RecyclerView recyclerViewResults;
    private List<TestResult> testResults;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocaleManager.loadLocale(this);  // Load the current locale
        setContentView(R.layout.activity_viewresults);

        initViews();
        setupClickListeners();
        updateTexts();
        loadTestResults();
        setupRecyclerView();
    }

    private void initViews() {
        tvCurrentFrequency = findViewById(R.id.tvCurrentFrequency);
        btnExportAllPatientData = findViewById(R.id.btnExportAllPatientData);
        btnDeleteAllPatientProfiles = findViewById(R.id.btnDeleteAllPatientProfiles);
        btnReturnToTitle = findViewById(R.id.returnToTitleButton);
        recyclerViewResults = findViewById(R.id.recyclerViewResults);
    }

    private void setupClickListeners() {
        btnExportAllPatientData.setOnClickListener(view -> exportAllPatientData());
        btnDeleteAllPatientProfiles.setOnClickListener(view -> deleteAllPatientProfiles());
        btnReturnToTitle.setOnClickListener(view -> finish());
    }

    private void updateTexts() {
        btnExportAllPatientData.setText(getString(R.string.export_all_patient_data));
        btnDeleteAllPatientProfiles.setText(getString(R.string.delete_all_patient_profiles));
        tvCurrentFrequency.setText(getString(R.string.current_frequency));
        btnReturnToTitle.setText(getString(R.string.return_to_title));
    }

    private void loadTestResults() {
        // Load test results from storage (e.g., database, file)
        // Here we are using dummy data for illustration purposes
        testResults = new ArrayList<>();
        testResults.add(new TestResult("Test 1", "Group A", new float[]{250, 500, 1000}, new float[]{20, 25, 30}));
        testResults.add(new TestResult("Test 2", "Group B", new float[]{250, 500, 1000}, new float[]{15, 20, 25}));
        // Add more test results as needed
    }

    private void setupRecyclerView() {
        TestResultAdapter adapter = new TestResultAdapter(this, testResults);
        recyclerViewResults.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewResults.setAdapter(adapter);
    }

    private void exportAllPatientData() {
        // Logic for exporting all patient data
    }

    private void deleteAllPatientProfiles() {
        // Logic for deleting all patient profiles
    }
}