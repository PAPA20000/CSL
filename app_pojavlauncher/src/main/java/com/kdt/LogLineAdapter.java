package com.kdt;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming log-line adapter (Req: animated terminal).
 *
 * Renders one row per log line so each arrival animates independently through
 * the RecyclerView item animator (fade + slide), giving the terminal a
 * premium line-by-line rhythm instead of one giant static text block.
 * The stream is capped — oldest lines are trimmed to protect memory.
 */
public class LogLineAdapter extends RecyclerView.Adapter<LogLineAdapter.LineVH> {

    public static final class LogLine {
        public final CharSequence text;
        public LogLine(CharSequence text) { this.text = text; }
    }

    private static final int MAX_LINES = 400;
    private final List<LogLine> mLines = new ArrayList<>();

    public void appendLine(LogLine line) {
        if (mLines.size() >= MAX_LINES) {
            int excess = 40; // trim in small batches, cheaper per-notify
            int remove = Math.min(excess, mLines.size());
            mLines.subList(0, remove).clear();
            notifyItemRangeRemoved(0, remove);
        }
        mLines.add(line);
        notifyItemInserted(mLines.size() - 1);
    }

    public void clear() {
        int size = mLines.size();
        mLines.clear();
        notifyItemRangeRemoved(0, size);
    }

    @NonNull
    @Override
    public LineVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                .inflate(net.kdt.pojavlaunch.R.layout.item_log_line, parent, false);
        return new LineVH(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull LineVH holder, int position) {
        holder.textView.setText(mLines.get(position).text);
    }

    @Override
    public int getItemCount() {
        return mLines.size();
    }

    static class LineVH extends RecyclerView.ViewHolder {
        final TextView textView;
        LineVH(View itemView) {
            super(itemView);
            textView = (TextView) itemView;
        }
    }
}
