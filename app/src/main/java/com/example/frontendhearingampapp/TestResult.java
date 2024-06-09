package com.example.frontendhearingampapp;

import java.util.List;

public class TestResult {
    private String testGroup;
    private String testType;
    private String frequency;
    private int leftEarDbThreshold;
    private int rightEarDbThreshold;
    private List<Integer> leftEarTestCounts;
    private List<Integer> rightEarTestCounts;

    public TestResult(String testGroup, String testType, String frequency, int leftEarDbThreshold, int rightEarDbThreshold, List<Integer> leftEarTestCounts, List<Integer> rightEarTestCounts) {
        this.testGroup = testGroup;
        this.testType = testType;
        this.frequency = frequency;
        this.leftEarDbThreshold = leftEarDbThreshold;
        this.rightEarDbThreshold = rightEarDbThreshold;
        this.leftEarTestCounts = leftEarTestCounts;
        this.rightEarTestCounts = rightEarTestCounts;
    }

    public String getTestGroup() {
        return testGroup;
    }

    public String getTestType() {
        return testType;
    }

    public String getFrequency() {
        return frequency;
    }

    public int getLeftEarDbThreshold() {
        return leftEarDbThreshold;
    }

    public int getRightEarDbThreshold() {
        return rightEarDbThreshold;
    }

    public List<Integer> getLeftEarTestCounts() {
        return leftEarTestCounts;
    }

    public List<Integer> getRightEarTestCounts() {
        return rightEarTestCounts;
    }
}