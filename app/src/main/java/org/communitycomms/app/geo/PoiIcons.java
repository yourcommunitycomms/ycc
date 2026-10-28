package org.communitycomms.app.geo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Simple drawn pictograms for POIs (no emoji fonts needed, so they look the same on Android 4.1).
 * Each sits on a dark rounded badge with a white rim so it reads on any map imagery.
 */
public final class PoiIcons {
    private PoiIcons() {
    }

    public static final int BLUE = 0xff42a5f5, RED = 0xffe53935, YELLOW = 0xfffdd835, WHITE = 0xffffffff;

    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF r = new RectF();

    static {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Draws icon centred on (cx, cy), roughly s pixels across. */
    public static void draw(Canvas c, int icon, float cx, float cy, float s) {
        float h = s / 2;
        // badge
        fill.setColor(0xcc101418);
        r.set(cx - h, cy - h, cx + h, cy + h);
        c.drawRoundRect(r, s * 0.22f, s * 0.22f, fill);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(s * 0.06f);
        c.drawRoundRect(r, s * 0.22f, s * 0.22f, stroke);
        float u = s / 10; // drawing unit
        switch (icon) {
            case 0:
                person(c, cx, cy, u, BLUE);
                break;
            case 1:
                person(c, cx, cy, u, RED);
                break;
            case 2:
                person(c, cx, cy, u, YELLOW);
                break;
            case 3: // car
                fill.setColor(WHITE);
                r.set(cx - 3.5f * u, cy - 0.5f * u, cx + 3.5f * u, cy + 1.6f * u);
                c.drawRoundRect(r, u, u, fill);
                r.set(cx - 2f * u, cy - 2f * u, cx + 2f * u, cy);
                c.drawRoundRect(r, u, u, fill);
                wheels(c, cx - 2f * u, cx + 2f * u, cy + 1.8f * u, u);
                break;
            case 4: // truck
                fill.setColor(WHITE);
                r.set(cx - 3.8f * u, cy - 2.6f * u, cx + 1f * u, cy + 1.4f * u);
                c.drawRect(r, fill);
                r.set(cx + 1.4f * u, cy - 1f * u, cx + 3.8f * u, cy + 1.4f * u);
                c.drawRoundRect(r, 0.6f * u, 0.6f * u, fill);
                wheels(c, cx - 2.6f * u, cx + 2.6f * u, cy + 1.8f * u, u);
                break;
            case 5: // bus
                fill.setColor(YELLOW);
                r.set(cx - 3.8f * u, cy - 2.4f * u, cx + 3.8f * u, cy + 1.4f * u);
                c.drawRoundRect(r, 0.8f * u, 0.8f * u, fill);
                fill.setColor(0xff263238);
                for (int i = 0; i < 4; i++) {
                    float x = cx - 3.2f * u + i * 1.7f * u;
                    c.drawRect(x, cy - 1.8f * u, x + 1.2f * u, cy - 0.4f * u, fill);
                }
                wheels(c, cx - 2.4f * u, cx + 2.4f * u, cy + 1.8f * u, u);
                break;
            case 6: // ambulance
                fill.setColor(WHITE);
                r.set(cx - 3.8f * u, cy - 2.4f * u, cx + 3.8f * u, cy + 1.4f * u);
                c.drawRoundRect(r, 0.8f * u, 0.8f * u, fill);
                fill.setColor(RED);
                c.drawRect(cx - 0.4f * u, cy - 2f * u, cx + 0.4f * u, cy + 1f * u, fill);
                c.drawRect(cx - 1.5f * u, cy - 0.9f * u, cx + 1.5f * u, cy - 0.1f * u, fill);
                wheels(c, cx - 2.4f * u, cx + 2.4f * u, cy + 1.8f * u, u);
                break;
            case 7: // helicopter
                fill.setColor(WHITE);
                c.drawOval(new RectF(cx - 2.6f * u, cy - 1.2f * u, cx + 1f * u, cy + 1.6f * u), fill);
                stroke.setStrokeWidth(0.6f * u);
                c.drawLine(cx + 0.8f * u, cy + 0.2f * u, cx + 3.8f * u, cy - 0.4f * u, stroke);
                c.drawLine(cx + 3.6f * u, cy - 1.2f * u, cx + 3.6f * u, cy + 0.4f * u, stroke);
                c.drawLine(cx - 3.8f * u, cy - 2.4f * u, cx + 2.4f * u, cy - 2.4f * u, stroke);
                c.drawLine(cx - 0.8f * u, cy - 2.4f * u, cx - 0.8f * u, cy - 1.2f * u, stroke);
                c.drawLine(cx - 2.4f * u, cy + 2.6f * u, cx + 0.8f * u, cy + 2.6f * u, stroke);
                break;
            case 8: { // plane, nose up
                fill.setColor(WHITE);
                Path p = new Path();
                p.moveTo(cx, cy - 4f * u);
                p.lineTo(cx + 0.6f * u, cy - 1.2f * u);
                p.lineTo(cx + 3.8f * u, cy + 0.6f * u);
                p.lineTo(cx + 3.8f * u, cy + 1.4f * u);
                p.lineTo(cx + 0.6f * u, cy + 0.6f * u);
                p.lineTo(cx + 0.5f * u, cy + 2.6f * u);
                p.lineTo(cx + 1.6f * u, cy + 3.6f * u);
                p.lineTo(cx - 1.6f * u, cy + 3.6f * u);
                p.lineTo(cx - 0.5f * u, cy + 2.6f * u);
                p.lineTo(cx - 0.6f * u, cy + 0.6f * u);
                p.lineTo(cx - 3.8f * u, cy + 1.4f * u);
                p.lineTo(cx - 3.8f * u, cy + 0.6f * u);
                p.lineTo(cx - 0.6f * u, cy - 1.2f * u);
                p.close();
                c.drawPath(p, fill);
                break;
            }
            case 9: // drone (quad)
                stroke.setStrokeWidth(0.6f * u);
                c.drawLine(cx - 2.4f * u, cy - 2.4f * u, cx + 2.4f * u, cy + 2.4f * u, stroke);
                c.drawLine(cx + 2.4f * u, cy - 2.4f * u, cx - 2.4f * u, cy + 2.4f * u, stroke);
                stroke.setStrokeWidth(0.4f * u);
                for (int dx = -1; dx <= 1; dx += 2) {
                    for (int dy = -1; dy <= 1; dy += 2) {
                        c.drawCircle(cx + dx * 2.6f * u, cy + dy * 2.6f * u, 1.2f * u, stroke);
                    }
                }
                fill.setColor(WHITE);
                c.drawCircle(cx, cy, 0.9f * u, fill);
                break;
            case 10:
                fill.setColor(RED);
                c.drawRect(cx - 3f * u, cy - 3f * u, cx + 3f * u, cy + 3f * u, fill);
                break;
            case 12: // photo: camera body, lens, flash bump
                fill.setColor(WHITE);
                r.set(cx - 3.6f * u, cy - 1.8f * u, cx + 3.6f * u, cy + 2.8f * u);
                c.drawRoundRect(r, 0.8f * u, 0.8f * u, fill);
                r.set(cx - 1.4f * u, cy - 2.8f * u, cx + 1.4f * u, cy - 1.4f * u);
                c.drawRoundRect(r, 0.4f * u, 0.4f * u, fill);
                fill.setColor(0xff263238);
                c.drawCircle(cx, cy + 0.5f * u, 1.7f * u, fill);
                fill.setColor(BLUE);
                c.drawCircle(cx, cy + 0.5f * u, 1.0f * u, fill);
                break;
            case 11:
                fill.setColor(BLUE);
                c.drawRect(cx - 3f * u, cy - 3f * u, cx + 3f * u, cy + 3f * u, fill);
                break;
            case 13: // toilets: man and woman
                person(c, cx - 2f * u, cy + 0.4f * u, 0.75f * u, BLUE);
                fill.setColor(RED);
                c.drawCircle(cx + 2f * u, cy - 1.3f * u, 1f * u, fill);
                tri(c, cx + 2f * u, cy - 0.3f * u, cx + 0.2f * u, cy + 3.2f * u, cx + 3.8f * u, cy + 3.2f * u, RED);
                break;
            case 14: // meeting point: flag
                stroke.setColor(WHITE);
                stroke.setStrokeWidth(0.6f * u);
                c.drawLine(cx - 2.4f * u, cy - 3.6f * u, cx - 2.4f * u, cy + 3.6f * u, stroke);
                fill.setColor(0xff43a047);
                r.set(cx - 2.1f * u, cy - 3.4f * u, cx + 3f * u, cy);
                c.drawRect(r, fill);
                break;
            case 15: // parked car: P sign
                fill.setColor(BLUE);
                r.set(cx - 3.4f * u, cy - 3.4f * u, cx + 3.4f * u, cy + 3.4f * u);
                c.drawRoundRect(r, u, u, fill);
                text(c, "P", cx, cy, 6f * u, WHITE);
                break;
            case 16: // fire front: a line of flames
                for (int i = -1; i <= 1; i++) flame(c, cx + i * 2.4f * u, cy + 0.6f * u, 0.6f * u);
                fill.setColor(0xffff7043);
                c.drawRect(cx - 3.8f * u, cy + 2.4f * u, cx + 3.8f * u, cy + 3.2f * u, fill);
                break;
            case 17: // spot fire: one flame
                flame(c, cx, cy, u);
                break;
            case 18: // fire truck: red truck with ladder
                fill.setColor(RED);
                r.set(cx - 3.8f * u, cy - 1.8f * u, cx + 1f * u, cy + 1.4f * u);
                c.drawRect(r, fill);
                r.set(cx + 1.4f * u, cy - 1f * u, cx + 3.8f * u, cy + 1.4f * u);
                c.drawRoundRect(r, 0.6f * u, 0.6f * u, fill);
                stroke.setColor(WHITE);
                stroke.setStrokeWidth(0.4f * u);
                c.drawLine(cx - 3.6f * u, cy - 2.6f * u, cx + 0.8f * u, cy - 2.6f * u, stroke);
                for (int i = 0; i < 4; i++) {
                    float x = cx - 3.2f * u + i * 1.3f * u;
                    c.drawLine(x, cy - 2.6f * u, x, cy - 1.8f * u, stroke);
                }
                wheels(c, cx - 2.6f * u, cx + 2.6f * u, cy + 1.8f * u, u);
                break;
            case 19: // tanker: truck with a round tank
                fill.setColor(0xff90caf9);
                r.set(cx - 3.8f * u, cy - 2f * u, cx + 1f * u, cy + 1.2f * u);
                c.drawRoundRect(r, 1.6f * u, 1.6f * u, fill);
                fill.setColor(RED);
                r.set(cx + 1.4f * u, cy - 1f * u, cx + 3.8f * u, cy + 1.4f * u);
                c.drawRoundRect(r, 0.6f * u, 0.6f * u, fill);
                wheels(c, cx - 2.6f * u, cx + 2.6f * u, cy + 1.8f * u, u);
                break;
            case 20: { // water point: drop
                Path p = new Path();
                p.moveTo(cx, cy - 3.8f * u);
                p.quadTo(cx + 3.2f * u, cy + 0.2f * u, cx + 2.4f * u, cy + 1.6f * u);
                p.quadTo(cx, cy + 4.6f * u, cx - 2.4f * u, cy + 1.6f * u);
                p.quadTo(cx - 3.2f * u, cy + 0.2f * u, cx, cy - 3.8f * u);
                fill.setColor(BLUE);
                c.drawPath(p, fill);
                break;
            }
            case 21: { // hostile: red diamond (military symbol style)
                Path p = new Path();
                p.moveTo(cx, cy - 3.8f * u);
                p.lineTo(cx + 3.8f * u, cy);
                p.lineTo(cx, cy + 3.8f * u);
                p.lineTo(cx - 3.8f * u, cy);
                p.close();
                fill.setColor(0xffff5252);
                c.drawPath(p, fill);
                break;
            }
            case 22: // friendly: blue rectangle
                fill.setColor(0xff64b5f6);
                c.drawRect(cx - 3.8f * u, cy - 2.4f * u, cx + 3.8f * u, cy + 2.4f * u, fill);
                break;
            case 23: // unknown: yellow square with ?
                fill.setColor(YELLOW);
                c.drawRect(cx - 3f * u, cy - 3f * u, cx + 3f * u, cy + 3f * u, fill);
                text(c, "?", cx, cy, 5.4f * u, 0xff101418);
                break;
            case 24: // observation post: triangle with eye dot
                tri(c, cx, cy - 3.6f * u, cx + 3.8f * u, cy + 3f * u, cx - 3.8f * u, cy + 3f * u, 0xff64b5f6);
                fill.setColor(0xff101418);
                c.drawCircle(cx, cy + 0.8f * u, 1f * u, fill);
                break;
            case 25: // danger: warning triangle with !
                tri(c, cx, cy - 3.8f * u, cx + 4f * u, cy + 3.2f * u, cx - 4f * u, cy + 3.2f * u, 0xffffa000);
                text(c, "!", cx, cy + 0.8f * u, 4.4f * u, 0xff101418);
                break;
            case 26: // first aid: red cross on white
                fill.setColor(WHITE);
                c.drawCircle(cx, cy, 3.6f * u, fill);
                fill.setColor(RED);
                c.drawRect(cx - 0.9f * u, cy - 2.6f * u, cx + 0.9f * u, cy + 2.6f * u, fill);
                c.drawRect(cx - 2.6f * u, cy - 0.9f * u, cx + 2.6f * u, cy + 0.9f * u, fill);
                break;
            case 27: // landing zone: H in a circle
                stroke.setColor(YELLOW);
                stroke.setStrokeWidth(0.6f * u);
                c.drawCircle(cx, cy, 3.4f * u, stroke);
                text(c, "H", cx, cy, 5f * u, YELLOW);
                break;
            case 28: // checkpoint: striped barrier
                fill.setColor(WHITE);
                c.drawRect(cx - 3.8f * u, cy - 0.9f * u, cx + 3.8f * u, cy + 0.9f * u, fill);
                fill.setColor(RED);
                for (int i = 0; i < 4; i += 2) {
                    float x = cx - 3.8f * u + i * 1.9f * u;
                    c.drawRect(x, cy - 0.9f * u, x + 1.9f * u, cy + 0.9f * u, fill);
                }
                fill.setColor(WHITE);
                c.drawRect(cx - 3.4f * u, cy + 0.9f * u, cx - 2.6f * u, cy + 3.6f * u, fill);
                break;
            case 29: // food / camp: tent
                tri(c, cx, cy - 3.4f * u, cx + 3.8f * u, cy + 3f * u, cx - 3.8f * u, cy + 3f * u, 0xff8bc34a);
                tri(c, cx, cy - 0.4f * u, cx + 1.2f * u, cy + 3f * u, cx - 1.2f * u, cy + 3f * u, 0xff101418);
                break;
            case 30: // road closed: no-entry sign
                fill.setColor(RED);
                c.drawCircle(cx, cy, 3.6f * u, fill);
                fill.setColor(WHITE);
                c.drawRect(cx - 2.4f * u, cy - 0.7f * u, cx + 2.4f * u, cy + 0.7f * u, fill);
                break;
            // ---- military unit markers (APP-6 style: blue rectangle friendly, red diamond hostile) ----
            case 31: case 32: case 33: case 34: case 35: case 36:
                friendlyFrame(c, cx, cy, u);
                unitSymbol(c, icon - 31, cx, cy, u, 0xff0d47a1);
                break;
            case 37: case 38: case 39:
                hostileFrame(c, cx, cy, u);
                unitSymbol(c, icon - 37, cx, cy, u, 0xff7f0000);
                break;
            case 40: // neutral: green square
                fill.setColor(0xff66bb6a);
                c.drawRect(cx - 3f * u, cy - 3f * u, cx + 3f * u, cy + 3f * u, fill);
                break;
            case 41: { // decision point: star
                Path p = new Path();
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI / 5 * i - Math.PI / 2;
                    float rr = (i % 2 == 0 ? 3.8f : 1.6f) * u;
                    float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                    if (i == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                p.close();
                fill.setColor(YELLOW);
                c.drawPath(p, fill);
                break;
            }
            case 42: // target: crosshair
                stroke.setColor(RED);
                stroke.setStrokeWidth(0.6f * u);
                c.drawCircle(cx, cy, 2.6f * u, stroke);
                c.drawLine(cx - 3.8f * u, cy, cx + 3.8f * u, cy, stroke);
                c.drawLine(cx, cy - 3.8f * u, cx, cy + 3.8f * u, stroke);
                break;
            case 43: // tree down
                fill.setColor(0xff795548);
                c.save();
                c.rotate(-60, cx, cy);
                c.drawRect(cx - 0.6f * u, cy - 0.5f * u, cx + 0.6f * u, cy + 3.6f * u, fill);
                fill.setColor(0xff43a047);
                c.drawCircle(cx, cy - 1.8f * u, 2.4f * u, fill);
                c.restore();
                break;
            case 44: { // power: lightning bolt
                Path p = new Path();
                p.moveTo(cx + 0.8f * u, cy - 3.8f * u);
                p.lineTo(cx - 2.4f * u, cy + 0.6f * u);
                p.lineTo(cx - 0.2f * u, cy + 0.6f * u);
                p.lineTo(cx - 1f * u, cy + 3.8f * u);
                p.lineTo(cx + 2.4f * u, cy - 0.8f * u);
                p.lineTo(cx + 0.2f * u, cy - 0.8f * u);
                p.close();
                fill.setColor(YELLOW);
                c.drawPath(p, fill);
                break;
            }
            case 45: // shelter: house
                tri(c, cx, cy - 3.6f * u, cx + 3.8f * u, cy - 0.4f * u, cx - 3.8f * u, cy - 0.4f * u, 0xffef6c00);
                fill.setColor(WHITE);
                c.drawRect(cx - 2.8f * u, cy - 0.4f * u, cx + 2.8f * u, cy + 3.4f * u, fill);
                fill.setColor(0xff101418);
                c.drawRect(cx - 0.8f * u, cy + 1f * u, cx + 0.8f * u, cy + 3.4f * u, fill);
                break;
            case 46: // boat / ship
                tri(c, cx - 0.2f * u, cy - 3.6f * u, cx - 0.2f * u, cy + 0.4f * u, cx - 2.8f * u, cy + 0.4f * u, WHITE);
                tri(c, cx + 0.4f * u, cy - 2.6f * u, cx + 0.4f * u, cy + 0.4f * u, cx + 2.6f * u, cy + 0.4f * u, WHITE);
                fill.setColor(BLUE);
                c.drawRect(cx - 3.8f * u, cy + 1f * u, cx + 3.8f * u, cy + 2.6f * u, fill);
                break;
            case 47: // grey dot (an imported icon this app does not have)
                fill.setColor(0xff9e9e9e);
                c.drawCircle(cx, cy, 2.6f * u, fill);
                break;
            default:
                fill.setColor(BLUE);
                c.drawRect(cx - 3f * u, cy - 3f * u, cx + 3f * u, cy + 3f * u, fill);
        }
    }

    private static void friendlyFrame(Canvas c, float cx, float cy, float u) {
        fill.setColor(0xff90caf9);
        c.drawRect(cx - 3.9f * u, cy - 2.6f * u, cx + 3.9f * u, cy + 2.6f * u, fill);
    }

    private static void hostileFrame(Canvas c, float cx, float cy, float u) {
        Path p = new Path();
        p.moveTo(cx, cy - 4f * u);
        p.lineTo(cx + 4f * u, cy);
        p.lineTo(cx, cy + 4f * u);
        p.lineTo(cx - 4f * u, cy);
        p.close();
        fill.setColor(0xffff8a80);
        c.drawPath(p, fill);
    }

    /** 0 infantry (X), 1 armour (oval), 2 artillery (dot), 3 recon (slash), 4 HQ (staff), 5 medical (cross). */
    private static void unitSymbol(Canvas c, int kind, float cx, float cy, float u, int colour) {
        stroke.setColor(colour);
        stroke.setStrokeWidth(0.45f * u);
        fill.setColor(colour);
        float w = 2.6f * u, h = 1.6f * u;
        switch (kind) {
            case 0:
                c.drawLine(cx - w, cy - h, cx + w, cy + h, stroke);
                c.drawLine(cx - w, cy + h, cx + w, cy - h, stroke);
                break;
            case 1:
                c.drawOval(new RectF(cx - 2.2f * u, cy - 1.1f * u, cx + 2.2f * u, cy + 1.1f * u), stroke);
                break;
            case 2:
                c.drawCircle(cx, cy, 0.9f * u, fill);
                break;
            case 3:
                c.drawLine(cx - w, cy + h, cx + w, cy - h, stroke);
                break;
            case 4:
                c.drawLine(cx - 3.9f * u, cy - 2.6f * u, cx - 3.9f * u, cy + 3.8f * u, stroke);
                text(c, "HQ", cx, cy, 2.6f * u, colour);
                break;
            default:
                c.drawLine(cx, cy - h, cx, cy + h, stroke);
                c.drawLine(cx - h, cy, cx + h, cy, stroke);
        }
    }

    /** Icon groups for the picker (scroll past the current profile's icons to find any of them). */
    public static final String[] CATEGORY_NAMES = {"People", "Vehicles, aircraft and boats", "Emergency and medical",
            "Fire", "Places and facilities", "Hazards", "Military units", "Military other", "Squares and dots"};
    public static final int[][] CATEGORIES = {
            {0, 1, 2, 22, 23},
            {3, 4, 5, 6, 18, 19, 7, 8, 9, 46},
            {6, 26, 27, 43, 44, 45, 25, 30},
            {16, 17, 18, 19, 20},
            {13, 14, 15, 29, 45, 28, 20, 12},
            {25, 30, 43, 44},
            {31, 32, 33, 34, 35, 36, 37, 38, 39, 40},
            {21, 22, 23, 24, 41, 42, 27, 28},
            {10, 11, 47}};

    private static void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3, int colour) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.close();
        fill.setColor(colour);
        c.drawPath(p, fill);
    }

    private static void flame(Canvas c, float cx, float cy, float u) {
        Path p = new Path();
        p.moveTo(cx, cy - 3.6f * u);
        p.quadTo(cx + 3f * u, cy - 0.4f * u, cx + 2.2f * u, cy + 1.8f * u);
        p.quadTo(cx, cy + 3.8f * u, cx - 2.2f * u, cy + 1.8f * u);
        p.quadTo(cx - 3f * u, cy - 0.4f * u, cx, cy - 3.6f * u);
        fill.setColor(0xffff5722);
        c.drawPath(p, fill);
        p.reset();
        p.moveTo(cx, cy - 0.8f * u);
        p.quadTo(cx + 1.5f * u, cy + 0.8f * u, cx + 1f * u, cy + 1.9f * u);
        p.quadTo(cx, cy + 2.8f * u, cx - 1f * u, cy + 1.9f * u);
        p.quadTo(cx - 1.5f * u, cy + 0.8f * u, cx, cy - 0.8f * u);
        fill.setColor(0xffffeb3b);
        c.drawPath(p, fill);
    }

    private static final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static void text(Canvas c, String t, float cx, float cy, float size, int colour) {
        textPaint.setColor(colour);
        textPaint.setTextSize(size);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2, textPaint);
    }

    private static void person(Canvas c, float cx, float cy, float u, int colour) {
        fill.setColor(colour);
        c.drawCircle(cx, cy - 2.2f * u, 1.4f * u, fill);
        Path p = new Path();
        p.moveTo(cx - 2.6f * u, cy + 3.6f * u);
        p.lineTo(cx - 2.2f * u, cy - 0.2f * u);
        p.quadTo(cx, cy - 0.8f * u, cx + 2.2f * u, cy - 0.2f * u);
        p.lineTo(cx + 2.6f * u, cy + 3.6f * u);
        p.close();
        c.drawPath(p, fill);
    }

    private static void wheels(Canvas c, float x1, float x2, float y, float u) {
        fill.setColor(0xff101418);
        c.drawCircle(x1, y, 1f * u, fill);
        c.drawCircle(x2, y, 1f * u, fill);
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(0.35f * u);
        c.drawCircle(x1, y, 1f * u, stroke);
        c.drawCircle(x2, y, 1f * u, stroke);
    }
}
