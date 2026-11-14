package com.scamera.app.gallery.ui;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.dng.DngFacts;
import com.scamera.app.util.Lang;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * P706 (owner, 2026-11-14: «В галерее сделай более подробные свойства фотографии»): the details sheet's sections below «Как
 * снято» - the file, the camera, the processing (SCAMERA's shot facts), the RAW (a DNG's tags), the place and HDR - each row a
 * title and a value, only the rows the file has a value for, a section without rows left out. The facts are read off the main
 * thread ({@link PhotoPropertiesReader}); this class only words them (pure: testable on the JVM).
 */
public final class PhotoProperties {
    private PhotoProperties() {}

    /** A titled group of rows: {title, value} or {title, value, link} (a uri the value opens: the map of a place). */
    public static final class Section {
        public final String title, tag;
        public final List<String[]> rows = new ArrayList<>();

        Section(String title, String tag) {
            this.title = title;
            this.tag = tag;
        }

        void add(String name, @Nullable String value) {
            if (value != null && !value.trim().isEmpty()) rows.add(new String[]{name, value.trim()});
        }

        void link(String name, @Nullable String value, String uri) {
            if (value != null && !value.trim().isEmpty()) rows.add(new String[]{name, value.trim(), uri});
        }
    }

    /** What is known of one file (NaN / 0 / -1 / null: not known). */
    public static final class Facts {
        // the file
        @Nullable public String name, path, mime, format;
        public long bytes, modifiedMs;
        public int width, height;
        public TimeZone zone = TimeZone.getDefault();
        // EXIF
        @Nullable public String taken, takenOffset, make, model, lensMake, lensModel, software, description;
        public double focalMm = Double.NaN, fNumber = Double.NaN, exposureS = Double.NaN, bias = Double.NaN, zoom = Double.NaN;
        public int focal35, iso, flash = -1, whiteBalance = -1, metering = -1, exposureProgram = -1, colourSpace = -1;
        public int rotation;
        public boolean mirrored;
        public double latitude = Double.NaN, longitude = Double.NaN, altitude = Double.NaN;
        // RAW and HDR
        @Nullable public DngFacts dng;
        public boolean ultraHdr;
    }

    /** The sections of {@code f}, in the sheet's order. */
    public static List<Section> sections(Facts f) {
        final List<Section> out = new ArrayList<>();
        out.add(file(f));
        out.add(camera(f));
        out.add(processing(f));
        if (f.dng != null) out.add(raw(f.dng));
        out.add(place(f));
        out.add(hdr(f));
        final List<Section> kept = new ArrayList<>();
        for (Section s : out) if (!s.rows.isEmpty()) kept.add(s);
        return kept;
    }

    static Section file(Facts f) {
        final Section s = new Section(Lang.t("Файл", "File"), "file");
        s.add(Lang.t("Имя", "Name"), f.name);
        s.add(Lang.t("Папка", "Folder"), folder(f.path));
        s.add(Lang.t("Путь к файлу", "File path"), f.path);
        final String type = f.format == null ? f.mime : f.mime == null ? f.format : f.format + " · " + f.mime;
        s.add(Lang.t("Формат", "Format"), type);
        s.add(Lang.t("Размер файла", "File size"), f.bytes > 0 ? size(f.bytes) : null);
        s.add(Lang.t("Разрешение", "Dimensions"), dimensions(f.width, f.height));
        s.add(Lang.t("Дата съёмки", "Taken"), joinSpace(PhotoMore.taken(f.taken), f.taken != null ? zoneLabel(f.takenOffset) : null));
        s.add(Lang.t("Изменён", "Modified"), f.modifiedMs > 0 ? modified(f.modifiedMs, f.zone) : null);
        return s;
    }

    static Section camera(Facts f) {
        final Section s = new Section(Lang.t("Камера", "Camera"), "camera");
        s.add(Lang.t("Устройство", "Device"), PhotoInfo.device(f.make, f.model));
        final String lens = f.lensModel == null ? null
                : f.lensMake != null && !f.lensModel.toLowerCase(Locale.ROOT).startsWith(f.lensMake.toLowerCase(Locale.ROOT).trim())
                ? f.lensMake.trim() + " " + f.lensModel.trim() : f.lensModel;
        s.add(Lang.t("Объектив", "Lens"), lens);
        s.add(Lang.t("Фокусное расстояние", "Focal length"), focal(f.focalMm, f.focal35));
        s.add(Lang.t("Диафрагма", "Aperture"), f.fNumber > 0 ? "f/" + decimal(f.fNumber, 2) : null);
        s.add(Lang.t("Выдержка", "Shutter speed"), shutter(f.exposureS));
        s.add(Lang.t("Чувствительность", "Sensitivity"), f.iso > 0 ? "ISO " + f.iso : null);
        s.add(Lang.t("Поправка экспозиции", "Exposure compensation"), PhotoMore.bias(f.bias));
        s.add(Lang.t("Режим экспозиции", "Exposure program"), program(f.exposureProgram));
        s.add(Lang.t("Вспышка", "Flash"), PhotoMore.flash(f.flash));
        s.add(Lang.t("Баланс белого", "White balance"), PhotoMore.whiteBalance(f.whiteBalance));
        s.add(Lang.t("Замер экспозиции", "Metering"), PhotoMore.metering(f.metering));
        s.add(Lang.t("Цифровой зум", "Digital zoom"), PhotoMore.zoom(f.zoom));
        s.add(Lang.t("Ориентация", "Orientation"), orientation(f.rotation, f.mirrored));
        s.add(Lang.t("Цветовое пространство", "Colour space"), PhotoMore.colourSpace(f.colourSpace));
        return s;
    }

    /** The processing: the Software tag and - a SCAMERA shot - the facts its ImageDescription carries. */
    static Section processing(Facts f) {
        final Section s = new Section(Lang.t("Обработка", "Processing"), "processing");
        final Map<String, String> shot = shotFacts(f.description);
        if (shot != null) {
            final String route = shot.get("Route");
            s.add(Lang.t("Склейка", "Merge"), route == null ? null : "Hybrid".equals(route) ? "SHDR" : route);
            s.add(Lang.t("Кадров склеено", "Frames merged"), shot.get("Frames"));
            s.add(Lang.t("Камера (ID)", "Camera ID"), shot.get("Camera"));
            s.add(Lang.t("Цвет", "Colour"), shot.get("Colour"));
            s.add(Lang.t("Плёнка", "Film"), shot.get("Film"));
            final String raw = shot.get("RAW");
            s.add("RAW", raw == null ? null : raw.startsWith("merged") ? Lang.t("Склеенная серия", "Merged burst")
                    : raw.startsWith("one") ? Lang.t("Один кадр", "One frame") : raw);
        } else if (f.description != null && f.description.trim().length() <= 200) {
            s.add(Lang.t("Описание", "Description"), f.description);
        }
        s.add(Lang.t("Программа", "Software"), f.software);
        return s;
    }

    static Section raw(DngFacts d) {
        final Section s = new Section("RAW (DNG)", "raw");
        s.add(Lang.t("Версия DNG", "DNG version"), d.version);
        s.add(Lang.t("Модель (UniqueCameraModel)", "UniqueCameraModel"), d.uniqueCameraModel);
        s.add(Lang.t("Размер RAW", "RAW size"), d.width > 0 && d.height > 0 ? d.width + " × " + d.height : null);
        s.add(Lang.t("Цветной фильтр (CFA)", "Colour filter (CFA)"), d.cfa);
        s.add(Lang.t("Разрядность", "Bit depth"), d.bits > 0 ? d.bits + Lang.t(" бит", " bit") : null);
        s.add(Lang.t("Сжатие", "Compression"), d.compression > 0 ? compression(d.compression) : null);
        s.add(Lang.t("Уровень чёрного", "Black level"), d.black > 0 ? decimal(d.black, 1) : null);
        s.add(Lang.t("Уровень белого", "White level"), d.white > 0 ? decimal(d.white, 0) : null);
        s.add(Lang.t("Базовая экспозиция", "Baseline exposure"), d.baselineExposure != 0 ? PhotoMore.bias(d.baselineExposure) : null);
        final List<String> m = new ArrayList<>();
        if (d.colorMatrices > 0) m.add("ColorMatrix ×" + d.colorMatrices);
        if (d.forwardMatrices > 0) m.add("ForwardMatrix ×" + d.forwardMatrices);
        s.add(Lang.t("Цветовые матрицы", "Colour matrices"), m.isEmpty() ? Lang.t("нет", "none") : String.join(", ", m));
        s.add(Lang.t("Профиль шума", "Noise profile"), d.noiseProfile ? Lang.t("есть", "present") : Lang.t("нет", "none"));
        s.add("OpcodeList1", d.opcodes1);
        s.add(Lang.t("OpcodeList2 (виньетирование)", "OpcodeList2 (lens shading)"), d.opcodes2);
        s.add("OpcodeList3", d.opcodes3);
        return s;
    }

    static Section place(Facts f) {
        final Section s = new Section(Lang.t("Место", "Location"), "place");
        if (Double.isFinite(f.latitude) && Double.isFinite(f.longitude) && !(f.latitude == 0 && f.longitude == 0)) {
            final String at = String.format(Locale.ROOT, "%.6f, %.6f", f.latitude, f.longitude);
            s.link(Lang.t("Координаты", "Coordinates"), at, mapUri(f.latitude, f.longitude));
            if (Double.isFinite(f.altitude)) s.add(Lang.t("Высота", "Altitude"), Math.round(f.altitude) + Lang.t(" м", " m"));
        }
        return s;
    }

    static Section hdr(Facts f) {
        final Section s = new Section("HDR", "hdr");
        if (f.ultraHdr) s.add(Lang.t("Карта усиления", "Gain map"), "Ultra HDR (JPEG)");
        return s;
    }

    // ---------------------------------------------------------------- the words

    /** The «key=value» lines of a SCAMERA ImageDescription («SCAMERA\n Frames=8\n Camera=0 ...»); null for another one. */
    @Nullable
    static Map<String, String> shotFacts(@Nullable String description) {
        if (description == null || !description.trim().startsWith("SCAMERA")) return null;
        final Map<String, String> out = new LinkedHashMap<>();
        for (String line : description.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq <= 0) continue;
            final String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) out.put(key, value);
        }
        return out;
    }

    /** The folder of {@code path} («DCIM/Camera» under the shared storage root, else the whole directory). */
    @Nullable
    static String folder(@Nullable String path) {
        if (path == null || !path.startsWith("/")) return null;
        final int slash = path.lastIndexOf('/');
        if (slash <= 0) return null;
        String dir = path.substring(0, slash);
        for (String root : new String[]{"/storage/emulated/0/", "/sdcard/"})
            if (dir.startsWith(root)) return dir.substring(root.length());
        return dir;
    }

    /** «4080 × 3072 · 12,5 Мп». */
    @Nullable
    static String dimensions(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        return w + " × " + h + " · " + decimal(w * (double) h / 1e6, 1) + Lang.t(" Мп", " MP");
    }

    /** «12,4 МБ (13 002 384 байт)». */
    static String size(long bytes) {
        final String exact = String.format(Locale.ROOT, "%,d", bytes).replace(',', ' ');
        final String big = bytes >= 1L << 20 ? decimal(bytes / (double) (1L << 20), 1) + Lang.t(" МБ", " MB")
                : bytes >= 1L << 10 ? Math.round(bytes / 1024.0) + Lang.t(" КБ", " KB") : null;
        return big == null ? exact + Lang.t(" байт", " bytes") : big + " (" + exact + Lang.t(" байт)", " bytes)");
    }

    /** «+03:00» of an EXIF OffsetTime, «» for none. */
    static String zoneLabel(@Nullable String offset) {
        if (offset == null || !offset.matches("[+-]\\d\\d:\\d\\d")) return "";
        return "UTC" + offset;
    }

    /** «14.11.2026 09:41:07 UTC+03:00» of {@code ms} in {@code zone}. */
    static String modified(long ms, TimeZone zone) {
        final SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT);
        f.setTimeZone(zone);
        final SimpleDateFormat z = new SimpleDateFormat("XXX", Locale.ROOT);
        z.setTimeZone(zone);
        final String offset = z.format(new Date(ms));
        return f.format(new Date(ms)) + " UTC" + ("Z".equals(offset) ? "+00:00" : offset);
    }

    /** «6,7 мм (24 мм экв.)». */
    @Nullable
    static String focal(double mm, int mm35) {
        final String real = mm > 0 ? decimal(mm, 1) + Lang.t(" мм", " mm") : null;
        final String equiv = mm35 > 0 ? mm35 + Lang.t(" мм экв.", " mm equiv.") : null;
        if (real == null) return equiv;
        return equiv == null ? real : real + " (" + equiv + ")";
    }

    /** «1/120 с», «2,5 с». */
    @Nullable
    static String shutter(double s) {
        if (!(s > 0) || !Double.isFinite(s)) return null;
        if (s < 1) return "1/" + Math.round(1 / s) + Lang.t(" с", " s");
        return decimal(s, 1) + Lang.t(" с", " s");
    }

    @Nullable
    static String program(int v) {
        switch (v) {
            case 1: return Lang.t("Ручной", "Manual");
            case 2: return Lang.t("Программный", "Program");
            case 3: return Lang.t("Приоритет диафрагмы", "Aperture priority");
            case 4: return Lang.t("Приоритет выдержки", "Shutter priority");
            default: return null;
        }
    }

    @Nullable
    static String orientation(int degrees, boolean mirrored) {
        if (degrees == 0 && !mirrored) return null;
        final String turn = PhotoMore.rotation(degrees);
        if (!mirrored) return turn;
        return turn == null ? Lang.t("Отражено", "Mirrored") : turn + Lang.t(", отражено", ", mirrored");
    }

    static String compression(int c) {
        switch (c) {
            case 1: return Lang.t("без сжатия", "uncompressed");
            case 7: return Lang.t("JPEG без потерь", "lossless JPEG");
            default: return DngFacts.compressionName(c);
        }
    }

    /** The map of a place («geo:» with a pin; any maps app takes it). */
    static String mapUri(double lat, double lon) {
        final String at = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon);
        return "geo:" + at + "?q=" + at;
    }

    /** As GalleryUi.decimal without the Android side: up to {@code digits} decimals, a comma in Russian. */
    static String decimal(double v, int digits) {
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        if (digits > 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return Lang.ru() ? s.replace('.', ',') : s;
    }

    @Nullable
    private static String joinSpace(@Nullable String a, @Nullable String b) {
        if (a == null) return null;
        return b == null || b.isEmpty() ? a : a + " " + b;
    }
}
