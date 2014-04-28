package org.blitzortung.android.map.overlay;

import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.Shape;
import android.location.Location;
import com.google.android.maps.GeoPoint;
import com.google.android.maps.OverlayItem;
import com.google.android.maps.Projection;
import org.blitzortung.android.data.Coordsys;
import org.blitzortung.android.data.beans.AbstractStroke;
import org.blitzortung.android.data.beans.RasterParameters;
import org.blitzortung.android.data.beans.Stroke;

public class StrokeOverlayItem extends OverlayItem implements Stroke {

	private final long timestamp;
	
	private final int multiplicity;

    private static final Point center = new Point();

    private static final Point topLeft = new Point();

    private static final Point bottomRight = new Point();

	public StrokeOverlayItem(AbstractStroke stroke) {
		super(Coordsys.toMapCoords(stroke.getLongitude(), stroke.getLatitude()), "", "");
        super.setMarker(new ShapeDrawable());

		timestamp = stroke.getTimestamp();		
		multiplicity = stroke.getMultiplicity();
	}
	
    @Override
	public long getTimestamp() {
		return timestamp;
	}

    @Override
    public Location getLocation(Location location) {
        final GeoPoint point = getPoint();
        location.setLongitude(point.getLongitudeE6() / 1e6);
        location.setLatitude(point.getLatitudeE6() / 1e6);
        return location;
    }

    @Override
    public int getMultiplicity() {
		return multiplicity;
	}

    @Override
    public void setMarker(Drawable drawable) {
        throw new IllegalStateException("cannot overwrite marker of stroke overlay item");
    }

    private ShapeDrawable getDrawable() {
        return (ShapeDrawable)getMarker(0);
    }
    
    public Shape getShape() {
        return getDrawable().getShape();
    }
    
    public void setShape(Shape shape) {
        getDrawable().setShape(shape);
    }

    public void updateShape(RasterParameters rasterParameters, Projection projection, int color, int textColor, int zoomLevel) {
        Shape shape = getShape();
        if (rasterParameters != null) {
            if (shape == null) {
                shape = new RasterShape();
            }
            RasterShape rasterShape = (RasterShape)shape;

            float lon_delta = rasterParameters.getLongitudeDelta() / 2.0f * 1e6f;
            float lat_delta = rasterParameters.getLatitudeDelta() / 2.0f * 1e6f;
            GeoPoint geoPoint = getPoint();
            projection.toPixels(geoPoint, center);
            projection.toPixels(new GeoPoint(
                    (int) (geoPoint.getLatitudeE6() + lat_delta),
                    (int) (geoPoint.getLongitudeE6() - lon_delta)), topLeft);
            projection.toPixels(new GeoPoint(
                    (int) (geoPoint.getLatitudeE6() - lat_delta),
                    (int) (geoPoint.getLongitudeE6() + lon_delta)), bottomRight);
            topLeft.offset(-center.x, -center.y);
            bottomRight.offset(-center.x, -center.y);
            rasterShape.update(topLeft, bottomRight, color, getMultiplicity(), textColor);
        } else {
            if (shape == null) {
                shape = new StrokeShape();
            }
            StrokeShape strokeShape = (StrokeShape) shape;
            strokeShape.update(zoomLevel + 1, color);

        }
        setShape(shape);
    }
}