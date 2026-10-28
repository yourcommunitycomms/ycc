package org.communitycomms.app.dryad;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/** Spinner/list adapter that draws each label in its sheet's colour. */
public final class ColourAdapter extends ArrayAdapter<String> {
    private final List<Integer> colours;

    public ColourAdapter(Context c, int layout, List<String> labels, List<Integer> colours) {
        super(c, layout, labels);
        this.colours = colours;
    }

    private View tint(View v, int pos) {
        if (v instanceof TextView && pos < colours.size() && colours.get(pos) != null) {
            ((TextView) v).setTextColor(colours.get(pos));
        }
        return v;
    }

    @Override
    public View getView(int pos, View convert, ViewGroup parent) {
        return tint(super.getView(pos, convert, parent), pos);
    }

    @Override
    public View getDropDownView(int pos, View convert, ViewGroup parent) {
        return tint(super.getDropDownView(pos, convert, parent), pos);
    }
}
