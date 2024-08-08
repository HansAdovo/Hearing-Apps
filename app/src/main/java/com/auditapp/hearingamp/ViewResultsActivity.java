package com.auditapp.hearingamp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ViewResultsActivity extends AppCompatActivity {

    private Button btnExportAllPatientData, btnDeleteAllPatientProfiles, btnReturnToTitle;
    private TextView tvCurrentFrequency;
    private ExpandableListView expandableListView;
    private ExpandableListAdapter expandableListAdapter;
    private List<String> listDataHeader;
    private HashMap<String, List<TestResult>> listDataChild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewresults);

        initViews();
        setupClickListeners();
        updateTexts();
        loadTestResults();
        setupExpandableListView();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Intent intent = new Intent(ViewResultsActivity.this, MainActivity.class);
        startActivity(intent);
        finish(); // Finish the current activity
    }

    private void initViews() {
        tvCurrentFrequency = findViewById(R.id.tvCurrentFrequency);
        btnExportAllPatientData = findViewById(R.id.btnExportAllPatientData);
        btnDeleteAllPatientProfiles = findViewById(R.id.btnDeleteAllPatientProfiles);
        btnReturnToTitle = findViewById(R.id.returnToTitleButton);
        expandableListView = findViewById(R.id.expandableListView);
    }

    private void setupClickListeners() {
        btnExportAllPatientData.setOnClickListener(view -> exportAllPatientData());
        btnDeleteAllPatientProfiles.setOnClickListener(view -> showDeleteConfirmationDialog());
        btnReturnToTitle.setOnClickListener(view -> {
            Intent intent = new Intent(ViewResultsActivity.this, MainActivity.class);
            startActivity(intent);
            finish(); // Finish the current activity
        });
    }

    private void updateTexts() {
        btnExportAllPatientData.setText(getString(R.string.export_all_patient_data));
        btnDeleteAllPatientProfiles.setText(getString(R.string.delete_all_patient_profiles));
        tvCurrentFrequency.setText(getString(R.string.current_frequency));
        btnReturnToTitle.setText(getString(R.string.return_to_title));
    }

    private void loadTestResults() {
        listDataHeader = new ArrayList<>();
        listDataChild = new HashMap<>();

        SharedPreferences sharedPreferences = getSharedPreferences("TestResults", MODE_PRIVATE);

        // Retrieve all saved test entries from shared preferences
        Map<String, ?> allEntries = sharedPreferences.getAll();
        Set<String> testKeys = new HashSet<>();

        // Extract unique test keys
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            Log.d("ViewResultsActivity", "Processing entry key: " + key);
            // Extract the test key by removing the part after the first underscore
            int underscoreIndex = key.indexOf('_');
            if (underscoreIndex != -1) {
                String testKey = key.substring(0, underscoreIndex);
                testKeys.add(testKey);
            }
        }

        // Convert testKeys to a list and sort it in descending order
        List<String> sortedTestKeys = new ArrayList<>(testKeys);
        Collections.sort(sortedTestKeys, (key1, key2) -> Long.compare(Long.parseLong(key2), Long.parseLong(key1)));

        // Log the sorted test keys
        Log.d("ViewResultsActivity", "Sorted test keys: " + sortedTestKeys.toString());

        // Define the frequencies array with "Hz"
        String[] frequencies = {"250 Hz", "500 Hz", "750 Hz", "1000 Hz", "1500 Hz", "2000 Hz", "3000 Hz", "4000 Hz", "6000 Hz", "8000 Hz"};

        for (String testKey : sortedTestKeys) {
            Log.d("ViewResultsActivity", "Processing testKey: " + testKey);
            String groupName = sharedPreferences.getString(testKey + "_group_name", "N/A");
            String patientName = sharedPreferences.getString(testKey + "_patient_name", "N/A");
            String testType = sharedPreferences.getString(testKey + "_test_type", "N/A");

            Log.d("ViewResultsActivity", "Loaded test: Group - " + groupName + ", Patient - " + patientName + ", Type - " + testType);

            List<TestResult> testResults = new ArrayList<>();
            for (String frequency : frequencies) {
                String frequencyKeyLeft = testKey + "_" + frequency + "_left";
                String frequencyKeyRight = testKey + "_" + frequency + "_right";
                Log.d("ViewResultsActivity", "Looking for keys: " + frequencyKeyLeft + " and " + frequencyKeyRight);

                int leftValue = sharedPreferences.getInt(frequencyKeyLeft, -1);
                int rightValue = sharedPreferences.getInt(frequencyKeyRight, -1);

                Log.d("ViewResultsActivity", "Processing frequency: " + frequency);
                Log.d("ViewResultsActivity", "leftValue for key " + frequencyKeyLeft + ": " + leftValue + ", rightValue for key " + frequencyKeyRight + ": " + rightValue);

                if (leftValue != -1 || rightValue != -1) {
                    String leftTestCountsStr = sharedPreferences.getString(frequencyKeyLeft + "_testCounts", "");
                    String rightTestCountsStr = sharedPreferences.getString(frequencyKeyRight + "_testCounts", "");

                    Log.d("ViewResultsActivity", "leftTestCountsStr: " + leftTestCountsStr + ", rightTestCountsStr: " + rightTestCountsStr);

                    List<Integer> leftTestCounts = parseTestCounts(leftTestCountsStr);
                    List<Integer> rightTestCounts = parseTestCounts(rightTestCountsStr);

                    Log.d("ViewResultsActivity", "leftTestCounts: " + leftTestCounts.toString() + ", rightTestCounts: " + rightTestCounts.toString());

                    testResults.add(new TestResult(groupName, testType, frequency, leftValue, rightValue, leftTestCounts, rightTestCounts));
                }
            }

            String header = groupName + " - " + patientName + " - " + testType;
            listDataHeader.add(header); // Add to the list
            listDataChild.put(header, testResults);

            Log.d("ViewResultsActivity", "Added header: " + header + " with " + testResults.size() + " test results");
        }

        setupExpandableListView();
    }

    private List<Integer> parseTestCounts(String testCountsStr) {
        List<Integer> testCounts = new ArrayList<>();
        if (!testCountsStr.isEmpty()) {
            Log.d("ViewResultsActivity", "Parsing test counts: " + testCountsStr);
            String[] parts = testCountsStr.replace("[", "").replace("]", "").split(", ");
            for (String part : parts) {
                try {
                    testCounts.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    Log.e("ViewResultsActivity", "Error parsing test count: " + part, e);
                }
            }
        }
        Log.d("ViewResultsActivity", "Parsed test counts: " + testCounts.toString());
        return testCounts;
    }

    private void setupExpandableListView() {
        expandableListAdapter = new ExpandableListAdapter(this, listDataHeader, listDataChild);
        expandableListView.setAdapter(expandableListAdapter);
        expandableListView.setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
            TestResult testResult = listDataChild.get(listDataHeader.get(groupPosition)).get(childPosition);
            tvCurrentFrequency.setText(getString(R.string.current_frequency) + ": " + testResult.getFrequency());
            updateGraphs(testResult);
            return false;
        });
    }

    @SuppressLint("StringFormatInvalid")
    private void updateGraphs(TestResult testResult) {
        GraphView leftEarGraph = findViewById(R.id.leftEarGraph);
        GraphView rightEarGraph = findViewById(R.id.rightEarGraph);

        updateSingleGraph(leftEarGraph, testResult.getLeftEarTestCounts(), getString(R.string.left_ear_graph_title), testResult.getTestType(), testResult.getTestGroup());
        updateSingleGraph(rightEarGraph, testResult.getRightEarTestCounts(), getString(R.string.right_ear_graph_title), testResult.getTestType(), testResult.getTestGroup());
    }

    private void updateSingleGraph(GraphView graph, List<Integer> testCounts, String titleFormat, String testType, String testGroup) {
        graph.removeAllSeries();

        DataPoint[] dataPoints;
        if (testCounts.size() == 1) {
            dataPoints = new DataPoint[]{
                    new DataPoint(0, testCounts.get(0)),
                    new DataPoint(1, testCounts.get(0))
            };
        } else {
            dataPoints = new DataPoint[testCounts.size()];
            for (int i = 0; i < testCounts.size(); i++) {
                dataPoints[i] = new DataPoint(i, testCounts.get(i));
            }
        }

        LineGraphSeries<DataPoint> series = new LineGraphSeries<>(dataPoints);
        series.setColor(Color.BLUE);
        series.setDrawDataPoints(true);
        series.setDataPointsRadius(10);
        series.setThickness(5);

        PointsGraphSeries<DataPoint> points = new PointsGraphSeries<>(dataPoints);
        points.setColor(Color.BLUE);
        points.setCustomShape((canvas, paint, x, y, dataPoint) -> {
            paint.setColor(Color.BLACK);
            paint.setTextSize(30);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format("%.0f", dataPoint.getY()), x, y - 20, paint);
        });

        graph.addSeries(series);
        graph.addSeries(points);
        graph.setTitle(String.format(titleFormat, testType, testGroup));

        // Configure grid label renderer
        graph.getGridLabelRenderer().setHorizontalAxisTitle(getString(R.string.axis_test_instance));
        graph.getGridLabelRenderer().setVerticalAxisTitle(getString(R.string.axis_volume_level_db));
        graph.getGridLabelRenderer().setHorizontalLabelsColor(Color.BLACK);
        graph.getGridLabelRenderer().setVerticalLabelsColor(Color.BLACK);
        graph.getGridLabelRenderer().setHorizontalAxisTitleColor(Color.BLACK);
        graph.getGridLabelRenderer().setVerticalAxisTitleColor(Color.BLACK);
        graph.getGridLabelRenderer().setGridColor(Color.LTGRAY);
        graph.getGridLabelRenderer().setHighlightZeroLines(true);

        // Set title color
        graph.setTitleColor(Color.BLACK);

        // Configure viewport
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(Math.max(1, dataPoints.length - 1));
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setScalable(true);
        graph.getViewport().setScrollable(true);

        // Find min and max Y values
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (DataPoint dp : dataPoints) {
            minY = Math.min(minY, (int) dp.getY());
            maxY = Math.max(maxY, (int) dp.getY());
        }

        // Set Y axis bounds with some padding
        graph.getViewport().setMinY(Math.max(0, minY - 10));
        graph.getViewport().setMaxY(maxY + 10);
        graph.getViewport().setYAxisBoundsManual(true);

        series.setOnDataPointTapListener((s, dataPoint) -> {
            String ear = titleFormat.contains("Left") ? "Left" : "Right";
            Toast.makeText(ViewResultsActivity.this, ear + " Ear: " + dataPoint.getY() + " dB", Toast.LENGTH_SHORT).show();
        });
    }

    private void exportAllPatientData() {
        // Logic for exporting all patient data
    }

    private void showDeleteConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.confirm_delete_all_profiles)
                .setCancelable(false)
                .setPositiveButton(R.string.confirm, (dialog, id) -> deleteAllPatientProfiles())
                .setNegativeButton(R.string.cancel, (dialog, id) -> dialog.cancel());
        AlertDialog alert = builder.create();
        alert.show();
    }

    private void deleteAllPatientProfiles() {
        SharedPreferences sharedPreferences = getSharedPreferences("TestResults", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();
        loadTestResults(); // Refresh the list after deletion
    }
}