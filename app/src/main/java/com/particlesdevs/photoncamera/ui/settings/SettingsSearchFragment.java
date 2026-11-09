package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.graphics.Color;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

/** Search keeps navigation separate from editing: a hit opens the real preference page. */
public class SettingsSearchFragment extends Fragment {
    public static final class Entry implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String key, page, title, path, summary;
        Entry(String key, String page, String title, String path, String summary) {
            this.key=key; this.page=page; this.title=title; this.path=path; this.summary=summary;
        }
        public boolean matches(String query) { return matches(query, null); }
        /** As {@link #matches(String)}, also looking in {@code other}: the words of this setting in the other UI language. */
        public boolean matches(String query, String other) {
            String haystack=normalize(title+" "+path+" "+summary+" "+key+(other==null ? "" : " "+other));
            for(String word:normalize(query).trim().split("\\s+")) if(!haystack.contains(word)) return false;
            return true;
        }
    }
    private static String normalize(String value) { return value.toLowerCase(Locale.ROOT).replace('ё','е'); }
    /**
     * Title, summary and page titles of every XML setting in the other UI language (English on a Russian UI, Russian
     * otherwise), by key: the search finds a setting by its Russian and by its English words whatever the UI language.
     * Read straight from {@code R.xml.preferences} with the resources of the other locale, so no preference is built;
     * settings generated in code have no entry and are found by their shown words only.
     */
    static HashMap<String,String> otherLanguage(Context context) {
        HashMap<String,String> words=new HashMap<>();
        if(context==null) return words;
        try {
            Configuration config=new Configuration(context.getResources().getConfiguration());
            config.setLocale(Lang.ru(context) ? Locale.ENGLISH : Locale.forLanguageTag("ru"));
            Resources res=context.createConfigurationContext(config).getResources();
            try(XmlResourceParser xml=res.getXml(R.xml.preferences)) {
                ArrayList<String> pages=new ArrayList<>();
                for(int event=xml.next(); event!=XmlPullParser.END_DOCUMENT; event=xml.next()) {
                    if(event==XmlPullParser.START_TAG) {
                        String key=null, title="", summary="";
                        for(int i=0;i<xml.getAttributeCount();i++) {
                            String name=xml.getAttributeName(i);
                            if("key".equals(name)) key=attribute(res, xml, i);
                            else if("title".equals(name)) title=attribute(res, xml, i);
                            else if("summary".equals(name)) summary=attribute(res, xml, i);
                        }
                        if(key!=null && !title.isEmpty()) words.put(key, title+" "+String.join(" ", pages)+" "+summary);
                        pages.add(xml.getDepth()==1 ? "" : title); // as index(): the root title is not part of a path
                    } else if(event==XmlPullParser.END_TAG && !pages.isEmpty()) pages.remove(pages.size()-1);
                }
            }
        } catch(Exception | LinkageError ignored) {
            // no words of the other language: the shown words are still searched
        }
        return words;
    }
    /**
     * 2026-10-27 (owner: settings anyone can set up alone, nothing lost): the words a row is found by besides its shown ones -
     * its words in the other UI language ({@link #otherLanguage}) and its help text ({@link SettingHelp}), which names a moved
     * row's former title and place, so «Базовая экспозиция AE» still finds «Яркость».
     */
    public static HashMap<String,String> searchWords(Context context, Iterable<Entry> entries) {
        HashMap<String,String> words=otherLanguage(context);
        if(context==null || entries==null) return words;
        for(Entry e:entries) {
            int help=SettingHelp.helpRes(e.key);
            if(help==0) continue;
            String text;
            try { text=context.getString(help); } catch(RuntimeException missing) { continue; }
            String known=words.get(e.key);
            words.put(e.key, known==null ? text : known+" "+text);
        }
        return words;
    }
    private static String attribute(Resources res, XmlResourceParser xml, int index) {
        int id=xml.getAttributeResourceValue(index, 0);
        if(id!=0) {
            try { return res.getString(id); } catch(RuntimeException notText) { return ""; }
        }
        String value=xml.getAttributeValue(index);
        return value==null ? "" : value;
    }
    public static ArrayList<Entry> index(PreferenceGroup root) {
        ArrayList<Entry> entries=new ArrayList<>();
        collect(root, root.getKey(), "", entries);
        return entries;
    }
    private static void collect(PreferenceGroup group, String page, String path, ArrayList<Entry> entries) {
        for(int i=0;i<group.getPreferenceCount();i++) {
            Preference p=group.getPreference(i);
            // P384: every module's sensor rows stay findable (only the chosen module's category is shown; a hit chooses its module);
            // P413: the lens page too (a module card's page, hidden as a row; a hit opens a module's lens page)
            if(!p.isVisible() && !com.particlesdevs.photoncamera.settings.SensorConfigPreferenceGenerator.isModuleCategory(p)
                    && !ModuleRows.LENS.equals(p.getKey())) continue;
            String title=p.getTitle()==null ? "" : p.getTitle().toString();
            String key=p.getKey();
            if(key!=null && !title.isEmpty()) {
                CharSequence summary=p.getSummary();
                entries.add(new Entry(key, p instanceof PreferenceScreen ? key : page, title, path,
                        summary==null ? "" : summary.toString()));
            }
            if(p instanceof PreferenceGroup) collect((PreferenceGroup)p,
                    p instanceof PreferenceScreen ? key : page,
                    path.isEmpty() ? title : path+" › "+title, entries);
        }
    }
    // The index is handed over in memory, not in the arguments: its ~0.7 MB went into the saved state at every stop and the binder
    // refused it (TransactionTooLargeException, the app died when sent to the background from the search, vivo 2026-11-03/04)
    private static volatile ArrayList<Entry> sEntries;
    public static SettingsSearchFragment create(ArrayList<Entry> entries) {
        sEntries=entries;
        return new SettingsSearchFragment();
    }
    private String query="";
    private ArrayList<Entry> entries;
    private HashMap<String,String> other=new HashMap<>(); // the other UI language's words and the help text, by key
    private final ArrayList<Entry> results=new ArrayList<>();
    private EditText input;
    private TextView count;
    private RecyclerView list;
    /** P574: the stand-in preference every hit row carries (SettingsCardDecoration draws the card behind such rows). */
    private androidx.preference.Preference hit;
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView v=new TextView(requireContext()); v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;
    }
    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        entries=sEntries;
        if(entries==null) { // a process restored without the index: back to the settings page (it builds the index again)
            entries=new ArrayList<>();
            getParentFragmentManager().popBackStack();
        }
        if(state!=null) query=state.getString("query", "");
        other=searchWords(getContext(), entries);
    }
    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(12),dp(16),0); root.setBackgroundColor(SettingsStyle.PAGE);
        // P6b: the shared header; P574: the concept's search capsule (#2C2C2E, 44dp, 22dp corners)
        SettingsStyle.Header header=SettingsStyle.header(requireContext(),()->requireActivity().getOnBackPressedDispatcher().onBackPressed(),null);
        header.heading.setText(Lang.t(getContext(),"Поиск","Search"));header.subtitle.setVisibility(View.GONE);
        header.crumb.setText(Lang.t(getContext(),"Настройки","Settings"));root.addView(header.view);
        LinearLayout search=new LinearLayout(requireContext());search.setGravity(android.view.Gravity.CENTER_VERTICAL);
        search.setBackground(SettingsStyle.shape(requireContext(),SettingsStyle.ROW,0,22));search.setPadding(dp(12),0,dp(4),0);
        search.setMinimumHeight(dp(44));
        android.widget.ImageView glass=new android.widget.ImageView(requireContext());glass.setImageResource(R.drawable.settings_ic_search);
        com.particlesdevs.photoncamera.ui.Icons.ink(glass, R.drawable.settings_ic_search, SettingsStyle.SECTION); // P570: tonal mono
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        search.addView(glass,new LinearLayout.LayoutParams(dp(20),dp(20)));
        input=new EditText(requireContext());input.setSingleLine(true);input.setTextColor(SettingsStyle.ROW_TITLE);input.setBackground(null);
        input.setHintTextColor(SettingsStyle.SECTION);input.setTextSize(17);input.setHint(Lang.t(getContext(),"Поиск","Search"));
        input.setPadding(dp(8),0,dp(4),0);
        input.setContentDescription(Lang.t(getContext(),"Поиск настройки","Search settings"));input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addView(input,new LinearLayout.LayoutParams(0,dp(44),1));
        // the concept's clear button: a 22dp grey disc with «×»
        TextView clear=label("×",15,SettingsStyle.ROW);clear.setGravity(android.view.Gravity.CENTER);
        clear.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);clear.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable disc=new android.graphics.drawable.GradientDrawable();disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        disc.setColor(0x73FFFFFF);
        android.graphics.drawable.InsetDrawable inset=new android.graphics.drawable.InsetDrawable(disc,dp(11));clear.setBackground(inset);
        clear.setContentDescription(Lang.t(getContext(),"Очистить поиск","Clear the search"));clear.setOnClickListener(v->input.setText(""));
        search.addView(clear,new LinearLayout.LayoutParams(dp(44),dp(44)));root.addView(search);
        count=label("",12,SettingsStyle.SECTION);count.setPadding(dp(16),dp(22),dp(16),dp(8));
        count.setAllCaps(true);count.setLetterSpacing(.08f);count.setTypeface(SettingsStyle.semibold());root.addView(count);
        list=new RecyclerView(requireContext());list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // P574: the hits in one card, as a group of the settings (SettingsCardDecoration: the card and the hairlines); each row
        // carries a stand-in preference (the decoration's mark of a card row)
        hit=new androidx.preference.Preference(requireContext());
        list.addItemDecoration(new SettingsCardDecoration(requireContext(),p->false));
        list.setClipToPadding(false);list.setPadding(0,0,0,dp(28));
        list.setAdapter(new ResultsAdapter());root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        input.setText(query);input.setSelection(input.length());filter(query);
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();filter(query);}
            public void afterTextChanged(Editable e){}
        });
        return root;
    }
    private void filter(String text) {
        results.clear();
        if(!text.trim().isEmpty()) for(Entry e:entries) if(e.matches(text, other.get(e.key)))results.add(e);
        count.setText(text.trim().isEmpty() ? Lang.t(getContext(),"Введите название или описание настройки","Type the name or description of a setting") :
                results.isEmpty() ? Lang.t(getContext(),"Ничего не найдено","Nothing found") : Lang.t(getContext(),"Найдено: ","Found: ")+results.size());
        list.getAdapter().notifyDataSetChanged();
    }
    @Override public void onResume() {
        super.onResume();View toolbar=requireActivity().findViewById(R.id.settings_toolbar);
        if(toolbar!=null)toolbar.setVisibility(View.GONE);
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state){super.onSaveInstanceState(state);state.putString("query",query);}
    @Override public void onDestroyView(){input=null;count=null;list=null;super.onDestroyView();}
    private CharSequence highlighted(String text) {
        SpannableString s=new SpannableString(text);String normalized=normalize(text);
        for(String word:normalize(query).trim().split("\\s+")) {
            if(word.isEmpty())continue;
            for(int at=normalized.indexOf(word);at>=0;at=normalized.indexOf(word,at+word.length()))
                s.setSpan(new ForegroundColorSpan(SettingsStyle.accent(requireContext())),at,at+word.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
    private class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final TextView title,path;
            Holder(LinearLayout row,TextView title,TextView path){super(row);this.title=title;this.path=path;}
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            LinearLayout row=new LinearLayout(requireContext());row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16),dp(9),dp(16),dp(9));row.setMinimumHeight(dp(52));row.setFocusable(true);
            row.setBackgroundResource(R.drawable.settings_row); // P574: a row of one card (the decoration draws it)
            TextView title=label("",16,SettingsStyle.ROW_TITLE),path=label("",13,SettingsStyle.SUMMARY);
            title.setTypeface(SettingsStyle.semibold());
            path.setPadding(0,dp(2),0,0);row.addView(title);row.addView(path);
            RecyclerView.LayoutParams lp=new RecyclerView.LayoutParams(-1,-2);
            row.setLayoutParams(lp);row.setTag(R.id.settings_bound_preference,hit);return new Holder(row,title,path);
        }
        @Override public void onBindViewHolder(@NonNull Holder h,int position){
            Entry e=results.get(position);h.title.setText(highlighted(e.title));h.path.setText(e.path);
            h.itemView.setOnClickListener(v->{
                InputMethodManager keyboard=(InputMethodManager)requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if(keyboard!=null)keyboard.hideSoftInputFromWindow(v.getWindowToken(),0);
                ((SettingsActivity)requireActivity()).openSearchResult(e);
            });
        }
        @Override public int getItemCount(){return results.size();}
    }
}
