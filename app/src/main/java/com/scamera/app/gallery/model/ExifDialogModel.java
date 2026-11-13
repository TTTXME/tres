package com.scamera.app.gallery.model;

import androidx.databinding.BaseObservable;
import androidx.databinding.Bindable;
import androidx.databinding.library.baseAdapters.BR;

import com.scamera.app.gallery.views.Histogram;

/**
 * What the viewer shows about the photo on screen (P59b details sheet, the pane labels, the histogram overlay): the values
 * as text ready to show (null when the file does not say), the stored size in pixels and the histogram once computed.
 */
public class ExifDialogModel extends BaseObservable {
    private Histogram.HistogramModel histogram;
    private String iso, shutter, aperture, focal, focal35, camera, description, resolution;
    private int width, height;

    @Bindable
    public Histogram.HistogramModel getHistogramModel() { return histogram; }

    public void setHistogramModel(Histogram.HistogramModel model) {
        histogram = model;
        notifyPropertyChanged(BR.histogramModel);
    }

    /** «ISO 100». */
    public String getIsoValue() { return iso; }
    public void setIsoValue(String v) { iso = v; }

    /** «1/120». */
    public String getShutterValue() { return shutter; }
    public void setShutterValue(String v) { shutter = v; }

    /** «f/1.75». */
    public String getFnumValue() { return aperture; }
    public void setFnumValue(String v) { aperture = v; }

    /** «6.6 mm» of the lens itself. */
    public String getFocalValue() { return focal; }
    public void setFocalValue(String v) { focal = v; }

    /** «23 mm» in 35 mm terms. */
    public String getFocal35Value() { return focal35; }
    public void setFocal35Value(String v) { focal35 = v; }

    /** P196: the phone as the file's EXIF names it («vivo X200 Ultra»). */
    public String getMakeModel() { return camera; }
    public void setMakeModel(String v) { camera = v; }

    /** The file's description (ImageDescription): a SCAMERA photo records its merge route there. */
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }

    public int getWidth() { return width; }
    public void setWidth(int v) { width = v; }

    public int getHeight() { return height; }
    public void setHeight(int v) { height = v; }

    /** P196: «8192×6144 · 50 Мп» of the pixels the file stores (PhotoInfo.resolution), null without a size. */
    public String getResolutionValue() { return resolution; }
    public void setResolutionValue(String v) { resolution = v; }
}
