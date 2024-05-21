package com.example.frontendhearingampapp;

public class TestResult {
    private String testName;
    private String testGroup;
    private float[] frequencies;
    private float[] volumeLevels;

    public TestResult(String testName, String testGroup, float[] frequencies, float[] volumeLevels) {
        this.testName = testName;
        this.testGroup = testGroup;
        this.frequencies = frequencies;
        this.volumeLevels = volumeLevels;
    }

    public String getTestName() {
        return testName;
    }

    public String getTestGroup() {
        return testGroup;
    }

    public float[] getFrequencies() {
        return frequencies;
    }

    public float[] getVolumeLevels() {
        return volumeLevels;
    }
}