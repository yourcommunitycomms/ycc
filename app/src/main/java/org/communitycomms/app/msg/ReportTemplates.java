package org.communitycomms.app.msg;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Built-in report formats plus the user's own custom templates. */
public final class ReportTemplates {
    /** How a field is filled: free text, a place (offers "insert my location"), or a date-time group. */
    public static final int TEXT = 0, LOCATION = 1, DTG = 2;

    public static final class Field {
        public final String label, prompt;
        public final int kind;
        /** Quick-insert choices shown as buttons (e.g. MEDEVAC precedence codes). */
        public final String[] options;

        Field(String label, String prompt, int kind, String... options) {
            this.label = label;
            this.prompt = prompt;
            this.kind = kind;
            this.options = options;
        }
    }

    public static final class Template {
        public final String code, title, about;
        public final List<Field> fields = new ArrayList<Field>();
        public final boolean custom;

        Template(String code, String title, String about, boolean custom) {
            this.code = code;
            this.title = title;
            this.about = about;
            this.custom = custom;
        }

        Template f(String label, String prompt, int kind, String... options) {
            fields.add(new Field(label, prompt, kind, options));
            return this;
        }
    }

    private static final String[] STATUS = {"GREEN", "AMBER", "RED", "BLACK"};
    private static final String[] PRECEDENCE = {"A - Urgent", "B - Urgent surgical", "C - Priority", "D - Routine",
            "E - Convenience"};
    private static final String[] EQUIPMENT = {"A - None", "B - Hoist", "C - Extraction equipment", "D - Ventilator"};
    private static final String[] PAT_TYPE = {"L - Litter", "A - Ambulatory"};
    private static final String[] SECURITY = {"N - No enemy troops in area", "P - Possible enemy troops",
            "E - Enemy in area, approach with caution", "X - Enemy in area, armed escort required"};
    private static final String[] MARKING = {"A - Panels", "B - Pyrotechnic signal", "C - Smoke signal", "D - None",
            "E - Other"};
    private static final String[] NATIONALITY = {"A - Own military", "B - Own civilian", "C - Other military",
            "D - Other civilian", "E - Detainee / EPW"};
    private static final String[] CBRN = {"None", "C - Chemical", "B - Biological", "R - Radiological", "N - Nuclear"};

    private static Template nineLine(String code, String title, String about) {
        return new Template(code, title, about, false)
                .f("1 Pickup location", "Where is the pickup site? Grid or lat/lon.", LOCATION)
                .f("2 Freq / callsign", "Radio channel or frequency, your callsign and suffix.", TEXT)
                .f("3 Patients by precedence", "Number of patients at each precedence, e.g. 1A 2C.", TEXT, PRECEDENCE)
                .f("4 Special equipment", "Special equipment needed at the pickup.", TEXT, EQUIPMENT)
                .f("5 Patients by type", "Number of litter and ambulatory patients, e.g. 1L 2A.", TEXT, PAT_TYPE)
                .f("6 Security at site", "Wartime: security at the pickup site. Peacetime: number and type of wounds.",
                        TEXT, SECURITY)
                .f("7 Site marking", "How the pickup site will be marked.", TEXT, MARKING)
                .f("8 Nationality / status", "Patient nationality and status, with numbers.", TEXT, NATIONALITY)
                .f("9 CBRN / terrain", "Wartime: CBRN contamination. Peacetime: terrain and obstacles at the site.",
                        TEXT, CBRN);
    }

    public static List<Template> builtIn() {
        List<Template> t = new ArrayList<Template>();
        t.add(new Template("SALUTE", "SALUTE", "Quick report of something seen: Size, Activity, Location, Unit, Time, Equipment.", false)
                .f("Size", "How many people or vehicles?", TEXT)
                .f("Activity", "What are they doing?", TEXT)
                .f("Location", "Where are they? Grid, lat/lon or a description.", LOCATION)
                .f("Unit / uniform", "Who are they? Unit, uniform, markings.", TEXT)
                .f("Time", "When did you see them?", DTG)
                .f("Equipment", "What equipment, weapons or vehicles?", TEXT));
        t.add(new Template("SPOTREP", "SPOTREP", "Spot report: a SALUTE with your callsign, assessment and remarks.", false)
                .f("1 DTG", "Date-time of this report.", DTG)
                .f("2 Reporting unit", "Your callsign or unit.", TEXT)
                .f("3 Size", "How many people or vehicles?", TEXT)
                .f("4 Activity", "What are they doing?", TEXT)
                .f("5 Location", "Where are they?", LOCATION)
                .f("6 Unit", "Who are they? Unit, uniform, markings.", TEXT)
                .f("7 Time observed", "When did you see them?", DTG)
                .f("8 Equipment", "What equipment or weapons?", TEXT)
                .f("9 Assessment", "What do you think they will do?", TEXT)
                .f("10 Remarks", "Anything else, and what you are doing about it.", TEXT));
        t.add(new Template("SITREP", "SITREP", "Situation report: where you are, what is happening, status and needs.", false)
                .f("DTG", "Date-time of this report.", DTG)
                .f("Unit / callsign", "Who is reporting?", TEXT)
                .f("Location", "Where are you?", LOCATION)
                .f("Own situation", "What you are doing now.", TEXT)
                .f("Threat / enemy activity", "Any threat or enemy activity seen.", TEXT)
                .f("Casualties", "Any casualties? Number and state.", TEXT)
                .f("Logistics", "Water, food, fuel, ammunition, batteries.", TEXT, STATUS)
                .f("Actions taken", "What you have done since the last report.", TEXT)
                .f("Intentions", "What you will do next.", TEXT)
                .f("Requests", "Anything you need.", TEXT)
                .f("Remarks", "Anything else.", TEXT));
        t.add(new Template("LACE", "LACE", "Status: Liquids, Ammunition, Casualties, Equipment.", false)
                .f("Liquids", "Water and fuel state.", TEXT, STATUS)
                .f("Ammunition", "Ammunition state.", TEXT, STATUS)
                .f("Casualties", "Casualties: number and state.", TEXT, STATUS)
                .f("Equipment", "Equipment state, anything damaged or lost.", TEXT, STATUS));
        t.add(new Template("ACE", "ACE", "Status: Ammunition, Casualties, Equipment.", false)
                .f("Ammunition", "Ammunition state.", TEXT, STATUS)
                .f("Casualties", "Casualties: number and state.", TEXT, STATUS)
                .f("Equipment", "Equipment state, anything damaged or lost.", TEXT, STATUS));
        t.add(nineLine("MEDEVAC", "9-line MEDEVAC", "Request medical evacuation by a medical asset."));
        t.add(nineLine("CASEVAC", "CASEVAC", "Casualty evacuation by any available transport, in the 9-line layout.")
                .f("Remarks", "Transport available or needed, route, anything else.", TEXT));
        t.add(new Template("MIST", "MIST (AT-MIST)", "Casualty handover: Age, Time, Mechanism, Injuries, Signs, Treatment.", false)
                .f("Age / ID", "Casualty age, sex or ID.", TEXT)
                .f("Time of injury", "When were they injured?", DTG)
                .f("Mechanism", "How were they injured?", TEXT)
                .f("Injuries", "Injuries found, head to toe.", TEXT)
                .f("Signs", "Pulse, breathing rate, AVPU.", TEXT, "A - Alert", "V - Voice", "P - Pain", "U - Unresponsive")
                .f("Treatment", "Treatment given and when.", TEXT));
        t.add(new Template("POSREP", "POSREP", "Position report: where you are and where you are going.", false)
                .f("DTG", "Date-time of this report.", DTG)
                .f("Callsign", "Who is reporting?", TEXT)
                .f("Position", "Where are you now?", LOCATION)
                .f("Heading", "Direction of travel.", TEXT, "N", "NE", "E", "SE", "S", "SW", "W", "NW", "Static")
                .f("Speed", "Speed of travel.", TEXT)
                .f("Destination", "Next waypoint or destination.", TEXT)
                .f("ETA", "Expected arrival time.", DTG)
                .f("Remarks", "Anything else.", TEXT));
        t.add(new Template("LOCSTAT", "LOCSTAT", "Location status: where you are and what state you are in.", false)
                .f("DTG", "Date-time of this report.", DTG)
                .f("Callsign", "Who is reporting?", TEXT)
                .f("Location", "Where are you?", LOCATION)
                .f("Status", "What is your status?", TEXT, "Static", "Moving", "Halted", "In position", "Returning")
                .f("Remarks", "Anything else.", TEXT));
        t.add(new Template("AAR", "AAR (after action review)", "What was meant to happen, what did, why, and what to change.", false)
                .f("Event", "Which task or event is this about?", TEXT)
                .f("Date / time", "When did it happen?", DTG)
                .f("Planned", "What was supposed to happen?", TEXT)
                .f("Happened", "What actually happened?", TEXT)
                .f("Why different", "Why was there a difference?", TEXT)
                .f("Went well", "What went well and should be kept?", TEXT)
                .f("Improve", "What should be done differently?", TEXT)
                .f("Actions", "Lessons and follow-up actions.", TEXT));
        return t;
    }

    // ---- custom templates ----

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("reports", Context.MODE_PRIVATE);
    }

    public static List<Template> custom(Context c) {
        List<Template> out = new ArrayList<Template>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("custom", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Template t = new Template("CUSTOM", o.getString("title"), "Your own template.", true);
                JSONArray f = o.getJSONArray("fields");
                for (int j = 0; j < f.length(); j++) {
                    String label = f.getString(j);
                    String low = label.toLowerCase(java.util.Locale.US);
                    int kind = low.contains("locat") || low.contains("grid") || low.contains("position") ? LOCATION
                            : low.contains("time") || low.contains("dtg") || low.contains("date") ? DTG : TEXT;
                    t.f(label, label, kind);
                }
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Saves (or replaces, matched by title) a custom template. */
    public static void saveCustom(Context c, String title, List<String> fields) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("custom", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!a.getJSONObject(i).getString("title").equals(title)) out.put(a.getJSONObject(i));
            }
            JSONArray f = new JSONArray();
            for (String s : fields) f.put(s);
            out.put(new JSONObject().put("title", title).put("fields", f));
            sp(c).edit().putString("custom", out.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    public static void deleteCustom(Context c, String title) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("custom", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!a.getJSONObject(i).getString("title").equals(title)) out.put(a.getJSONObject(i));
            }
            sp(c).edit().putString("custom", out.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    /** Date-time group in the usual DDHHMMZ MON YY form, in UTC. */
    public static String dtgNow() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("ddHHmm'Z' MMM yy", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date()).toUpperCase(java.util.Locale.US);
    }
}
