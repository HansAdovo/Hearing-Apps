package com.example.frontendhearingampapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.Set;

public class TestInstructionActivity extends AppCompatActivity {

    private Button btnStartTest;
    private TextView txtInstructions;
    private Button btnReturnToTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_instruction);

        btnStartTest = findViewById(R.id.btnStartTest);
        txtInstructions = findViewById(R.id.txtInstructions);
        btnReturnToTitle = findViewById(R.id.btnReturnToTitle);

        String instructions = getString(R.string.test_instructions);
        txtInstructions.setText(instructions);

        btnStartTest.setOnClickListener(view -> {
            Intent intent = new Intent(TestInstructionActivity.this, TestActualActivity.class);

            SharedPreferences prefs = getSharedPreferences("TestActivityPrefs", MODE_PRIVATE);
            Set<String> frequencySet = prefs.getStringSet("TestSequence", new HashSet<>());
            String[] frequencies = frequencySet.toArray(new String[0]);
            String earOrder = prefs.getString("EarOrder", "leftToRight");

            intent.putExtra("frequencies", frequencies);
            intent.putExtra("earOrder", earOrder);

            Log.d("TestInstructionActivity", "EarOrder passed: " + earOrder);

            String patientGroup = getIntent().getStringExtra("patientGroup");
            String patientName = getIntent().getStringExtra("patientName");

            intent.putExtra("patientGroup", patientGroup);
            intent.putExtra("patientName", patientName);

            startActivity(intent);
        });

        btnReturnToTitle.setOnClickListener(view -> {
            Intent intent = new Intent(TestInstructionActivity.this, MainActivity.class);
            startActivity(intent);
        });
    }
}