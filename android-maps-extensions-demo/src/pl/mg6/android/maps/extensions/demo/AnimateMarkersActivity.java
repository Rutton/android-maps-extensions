/*
 * Copyright (C) 2013 Maciej Górski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.mg6.android.maps.extensions.demo;

import java.util.Random;

import pl.mg6.android.maps.extensions.AnimationSettings;
import pl.mg6.android.maps.extensions.GoogleMap;
import pl.mg6.android.maps.extensions.GoogleMap.OnMarkerClickListener;
import pl.mg6.android.maps.extensions.Marker;
import pl.mg6.android.maps.extensions.MarkerOptions;
import pl.mg6.android.maps.extensions.SupportMapFragment;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.CycleInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;

public class AnimateMarkersActivity extends FragmentActivity {

	private Random random = new Random();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.simple_map);

		FragmentManager fm = getSupportFragmentManager();
		SupportMapFragment f = (SupportMapFragment) fm.findFragmentById(R.id.map);
		GoogleMap map = f.getExtendedMap();

		map.addMarker(new MarkerOptions().title("RED").position(new LatLng(-15, -15)).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
		map.addMarker(new MarkerOptions().title("GREEN").position(new LatLng(-15, 15)).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
		map.addMarker(new MarkerOptions().title("BLUE").position(new LatLng(15, -15)).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
		map.addMarker(new MarkerOptions().title("YELLOW").position(new LatLng(15, 15)).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW)));

		final Marker.AnimationCallback callback = new Marker.AnimationCallback() {
			@Override
			public void onFinish(Marker marker) {
				Toast.makeText(AnimateMarkersActivity.this, "Animation finished: " + marker.getTitle(), Toast.LENGTH_SHORT).show();
			}

			@Override
			public void onCancel(Marker marker, CancelReason reason) {
				Toast.makeText(AnimateMarkersActivity.this, "Animation canceled: " + marker.getTitle() + ", reason: " + reason, Toast.LENGTH_SHORT).show();
			}
		};
		map.setOnMarkerClickListener(new OnMarkerClickListener() {

			@Override
			public boolean onMarkerClick(Marker marker) {
				LatLng position = marker.getPosition();
				LatLng targetPosition = randomPositionAcrossTheOcean(position);
				long duration = random.nextInt(1500) + 1500;
				Interpolator interpolator = randomInterpolator();
				AnimationSettings settings = new AnimationSettings().duration(duration).interpolator(interpolator);
				marker.animatePosition(targetPosition, settings, callback);
				return true;
			}
		});
	}

	private LatLng randomPositionAcrossTheOcean(LatLng position) {
		double lat;
		double lng;
		if (position.latitude < 0) {
			lat = random.nextDouble() * 10 + 10;
		} else {
			lat = random.nextDouble() * 10 - 20;
		}
		if (position.longitude < 0) {
			lng = random.nextDouble() * 10 + 10;
		} else {
			lng = random.nextDouble() * 10 - 20;
		}
		return new LatLng(lat, lng);
	}

	private Interpolator randomInterpolator() {
		int val = random.nextInt(14);
		switch (val) {
			case 0:
				return new LinearInterpolator();
			case 1:
				return new AccelerateDecelerateInterpolator();
			case 2:
				return new AccelerateInterpolator();
			case 3:
				return new AccelerateInterpolator(6.0f);
			case 4:
				return new DecelerateInterpolator();
			case 5:
				return new DecelerateInterpolator(6.0f);
			case 6:
				return new BounceInterpolator();
			case 7:
				return new AnticipateOvershootInterpolator();
			case 8:
				return new AnticipateOvershootInterpolator(6.0f);
			case 9:
				return new AnticipateInterpolator();
			case 10:
				return new AnticipateInterpolator(6.0f);
			case 11:
				return new OvershootInterpolator();
			case 12:
				return new OvershootInterpolator(6.0f);
			case 13:
				return new CycleInterpolator(1.25f);
		}
		throw new RuntimeException();
	}
}
