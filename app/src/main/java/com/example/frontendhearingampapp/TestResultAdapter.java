package com.example.frontendhearingampapp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import java.util.List;

public class TestResultAdapter extends RecyclerView.Adapter<TestResultAdapter.TestResultViewHolder> {

    private final Context context;
    private final List<TestResult> testResults;

    public TestResultAdapter(Context context, List<TestResult> testResults) {
        this.context = context;
        this.testResults = testResults;
    }

    @NonNull
    @Override
    public TestResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_test_result, parent, false);
        return new TestResultViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TestResultViewHolder holder, int position) {
        TestResult testResult = testResults.get(position);
        holder.testNameTextView.setText(testResult.getTestName());
        holder.testGroupTextView.setText(testResult.getTestGroup());

        // Prepare data points for the chart
        DataPoint[] dataPoints = new DataPoint[testResult.getFrequencies().length];
        for (int i = 0; i < testResult.getFrequencies().length; i++) {
            dataPoints[i] = new DataPoint(testResult.getFrequencies()[i], testResult.getVolumeLevels()[i]);
        }

        // Create a series and add data points to it
        LineGraphSeries<DataPoint> series = new LineGraphSeries<>(dataPoints);

        // Add series to the graph
        holder.graph.addSeries(series);

        // Customize graph appearance
        holder.graph.setTitle(testResult.getTestName() + " (" + testResult.getTestGroup() + ")");
        holder.graph.getGridLabelRenderer().setHorizontalAxisTitle("Frequency (Hz)");
        holder.graph.getGridLabelRenderer().setVerticalAxisTitle("Volume Level (dB)");
    }

    @Override
    public int getItemCount() {
        return testResults.size();
    }

    public static class TestResultViewHolder extends RecyclerView.ViewHolder {
        TextView testNameTextView;
        TextView testGroupTextView;
        GraphView graph;

        public TestResultViewHolder(@NonNull View itemView) {
            super(itemView);
            testNameTextView = itemView.findViewById(R.id.testNameTextView);
            testGroupTextView = itemView.findViewById(R.id.testGroupTextView);
            graph = itemView.findViewById(R.id.graph);
        }
    }
}