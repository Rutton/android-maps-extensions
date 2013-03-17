package pl.mg6.android.maps.extensions.demo;

import pl.mg6.android.maps.extensions.ClusteringSettings.IconProvider;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

public class DemoIconProvider implements IconProvider {

	private static final int[] res = { R.drawable.m1, R.drawable.m2, R.drawable.m3, R.drawable.m4, R.drawable.m5 };

	private static final int[] forCounts = { 10, 100, 1000, 10000, Integer.MAX_VALUE };

	private Bitmap[] baseBitmaps;

	private Paint paint;
	private Rect bounds = new Rect();

	public DemoIconProvider(Resources resources) {
		baseBitmaps = new Bitmap[res.length];
		for (int i = 0; i < res.length; i++) {
			baseBitmaps[i] = BitmapFactory.decodeResource(resources, res[i]);
		}
		paint = new Paint();
		paint.setAntiAlias(true);
		paint.setColor(Color.WHITE);
		paint.setTextAlign(Align.CENTER);
		paint.setTextSize(resources.getDimension(R.dimen.text_size));
	}

	@Override
	public BitmapDescriptor getIcon(int markersCount) {
		Bitmap base;
		int i = 0;
		do {
			base = baseBitmaps[i];
		} while (markersCount >= forCounts[i++]);

		Bitmap bitmap = base.copy(Config.ARGB_8888, true);

		String text = String.valueOf(markersCount);
		paint.getTextBounds(text, 0, text.length(), bounds);
		float x = bitmap.getWidth() / 2.0f;
		float y = (bitmap.getHeight() - bounds.height()) / 2.0f - bounds.top;

		Canvas canvas = new Canvas(bitmap);
		canvas.drawText(text, x, y, paint);

		return BitmapDescriptorFactory.fromBitmap(bitmap);
	}
}
