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
package pl.mg6.android.maps.extensions.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.mg6.android.maps.extensions.AnimationSettings;
import pl.mg6.android.maps.extensions.ClusteringSettings;
import pl.mg6.android.maps.extensions.ClusteringSettings.IconDataProvider;
import pl.mg6.android.maps.extensions.Marker;
import pl.mg6.android.maps.extensions.utils.SphericalMercator;
import android.support.v4.util.LongSparseArray;

import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.VisibleRegion;

class GridClusteringStrategy implements ClusteringStrategy {

	private static final boolean DEBUG_GRID = false;
	private DebugHelper debugHelper;

	private final MarkerOptions markerOptions = new MarkerOptions();

	private boolean addMarkersDynamically;
	private double baseClusterSize;
	private IGoogleMap map;
	private Map<DelegatingMarker, ClusterMarker> markers;
	private double clusterSize;
	private int oldZoom, zoom;
	private int[] visibleClusters = new int[4];

	private LongSparseArray<ClusterMarker> clusters = new LongSparseArray<ClusterMarker>();

	private ClusterRefresher refresher;
	private IconDataProvider iconDataProvider;

	public GridClusteringStrategy(ClusteringSettings settings, IGoogleMap map, List<DelegatingMarker> markers, ClusterRefresher refresher) {
		this.iconDataProvider = settings.getIconDataProvider();
		this.addMarkersDynamically = settings.isAddMarkersDynamically();
		this.baseClusterSize = settings.getClusterSize();
		this.map = map;
		this.markers = new HashMap<DelegatingMarker, ClusterMarker>();
		for (DelegatingMarker m : markers) {
			if (m.isVisible()) {
				this.markers.put(m, null);
			}
		}
		this.refresher = refresher;
		this.oldZoom = -1;
		this.zoom = Math.round(map.getCameraPosition().zoom);
		this.clusterSize = calculateClusterSize(zoom);
		recalculate();
	}

	@Override
	public void cleanup() {
		for (int i = 0; i < clusters.size(); i++) {
			ClusterMarker cluster = clusters.valueAt(i);
			cluster.cleanup();
		}
		clusters.clear();
		markers.clear();
		refresher.cleanup();
		if (DEBUG_GRID) {
			if (debugHelper != null) {
				debugHelper.cleanup();
			}
		}
	}

	@Override
	public void onCameraChange(CameraPosition cameraPosition) {
		oldZoom = zoom;
		zoom = Math.round(cameraPosition.zoom);
		double clusterSize = calculateClusterSize(zoom);
		if (this.clusterSize != clusterSize) {
			this.clusterSize = clusterSize;
			recalculate();
		} else if (addMarkersDynamically) {
			addMarkersInVisibleRegion();
		}
		if (DEBUG_GRID) {
			if (debugHelper == null) {
				debugHelper = new DebugHelper();
			}
			debugHelper.drawDebugGrid(map, clusterSize);
		}
	}

	@Override
	public void onAdd(DelegatingMarker marker) {
		if (!marker.isVisible()) {
			return;
		}
		addMarker(marker);
	}

	private void addMarker(DelegatingMarker marker) {
		LatLng position = marker.getPosition();
		long clusterId = calculateClusterId(position);
		ClusterMarker cluster = findClusterById(clusterId);
		cluster.add(marker);
		markers.put(marker, cluster);
		if (!addMarkersDynamically || isPositionInVisibleClusters(position)) {
			refresh(cluster);
		}
	}

	private boolean isPositionInVisibleClusters(LatLng position) {
		int y = convLat(position.latitude);
		int x = convLng(position.longitude);
		int[] b = visibleClusters;
		return b[0] <= y && y <= b[2] && (b[1] <= x && x <= b[3] || b[1] > b[3] && (b[1] <= x || x <= b[3]));
	}

	@Override
	public void onRemove(DelegatingMarker marker) {
		if (!marker.isVisible()) {
			return;
		}
		removeMarker(marker);
	}

	private void removeMarker(DelegatingMarker marker) {
		ClusterMarker cluster = markers.remove(marker);
		if (cluster != null) {
			cluster.remove(marker);
			refresh(cluster);
		}
	}

	@Override
	public void onPositionChange(DelegatingMarker marker) {
		if (!marker.isVisible()) {
			return;
		}
		ClusterMarker oldCluster = markers.get(marker);
		if (oldCluster != null && isMarkerInCluster(marker, oldCluster)) {
			refresh(oldCluster);
		} else {
			if (oldCluster != null) {
				oldCluster.remove(marker);
				refresh(oldCluster);
			}
			addMarker(marker);
		}
	}

	@Override
	public Marker map(com.google.android.gms.maps.model.Marker original) {
		for (int i = 0; i < clusters.size(); i++) {
			ClusterMarker cluster = clusters.valueAt(i);
			if (original.equals(cluster.getVirtual())) {
				return cluster;
			}
		}
		return null;
	}

	@Override
	public List<Marker> getDisplayedMarkers() {
		List<Marker> displayedMarkers = new ArrayList<Marker>();
		for (int i = 0; i < clusters.size(); i++) {
			ClusterMarker cluster = clusters.valueAt(i);
			Marker displayedMarker = cluster.getDisplayedMarker();
			if (displayedMarker != null) {
				displayedMarkers.add(displayedMarker);
			}
		}
		return displayedMarkers;
	}

	@Override
	public float getMinZoomLevelNotClustered(Marker marker) {
		if (!markers.containsKey(marker)) {
			throw new UnsupportedOperationException("marker is not visible or is a cluster");
		}
		int zoom = 0;
		while (zoom <= 25 && hasCollision(marker, zoom)) {
			zoom++;
		}
		if (zoom > 25) {
			return Float.POSITIVE_INFINITY;
		}
		return zoom;
	}

	private boolean hasCollision(Marker marker, int zoom) {
		double clusterSize = calculateClusterSize(zoom);
		LatLng position = marker.getPosition();
		int x = (int) (SphericalMercator.scaleLongitude(position.longitude) / clusterSize);
		int y = (int) (SphericalMercator.scaleLatitude(position.latitude) / clusterSize);
		for (DelegatingMarker m : markers.keySet()) {
			if (m.equals(marker)) {
				continue;
			}
			LatLng mPosition = m.getPosition();
			int mX = (int) (SphericalMercator.scaleLongitude(mPosition.longitude) / clusterSize);
			if (x != mX) {
				continue;
			}
			int mY = (int) (SphericalMercator.scaleLatitude(mPosition.latitude) / clusterSize);
			if (y == mY) {
				return true;
			}
		}
		return false;
	}

	private boolean isMarkerInCluster(DelegatingMarker marker, ClusterMarker cluster) {
		long clusterId = cluster.getClusterId();
		long markerClusterId = calculateClusterId(marker.getPosition());
		return clusterId == markerClusterId;
	}

	private ClusterMarker findClusterById(long clusterId) {
		ClusterMarker cluster = clusters.get(clusterId);
		if (cluster == null) {
			cluster = new ClusterMarker(this);
			cluster.setClusterId(clusterId);
			clusters.put(clusterId, cluster);
		}
		return cluster;
	}

	@Override
	public void onVisibilityChangeRequest(DelegatingMarker marker, boolean visible) {
		if (visible) {
			addMarker(marker);
		} else {
			removeMarker(marker);
			marker.changeVisible(false);
		}
	}

	@Override
	public void onShowInfoWindow(DelegatingMarker marker) {
		if (!marker.isVisible()) {
			return;
		}
		ClusterMarker cluster = markers.get(marker);
		if (cluster.getMarkersInternal().size() == 1) {
			cluster.refresh();
			marker.forceShowInfoWindow();
		}
	}

	private void refresh(ClusterMarker cluster) {
		refresher.refresh(cluster);
	}

	private void recalculate() {
		if (addMarkersDynamically) {
			calculateVisibleClusters();
		}
		if (oldZoom == -1) {
			for (DelegatingMarker marker : markers.keySet()) {
				addMarker(marker);
			}
		} else {
			if (zoomedIn()) {
				splitClusters();
			} else {
				joinClusters();
			}
		}
		refresher.refreshAll();
	}

	private boolean zoomedIn() {
		return zoom > oldZoom;
	}

	private void splitClusters() {
		LongSparseArray<ClusterMarker> newClusters = new LongSparseArray<ClusterMarker>();
		for (int i = 0; i < clusters.size(); i++) {
			ClusterMarker cluster = clusters.valueAt(i);
			List<DelegatingMarker> ms = cluster.getMarkersInternal();
			if (ms.isEmpty()) {
				cluster.removeVirtual();
				continue;
			}
			long[] clusterIds = new long[ms.size()];
			boolean allSame = true;
			for (int j = 0; j < ms.size(); j++) {
				clusterIds[j] = calculateClusterId(ms.get(j).getPosition());
				if (clusterIds[j] != clusterIds[0]) {
					allSame = false;
				}
			}
			if (allSame) {
				cluster.setClusterId(clusterIds[0]);
				newClusters.put(clusterIds[0], cluster);
			} else {
				cluster.removeVirtual();
				for (int j = 0; j < ms.size(); j++) {
					cluster = newClusters.get(clusterIds[j]);
					if (cluster == null) {
						cluster = new ClusterMarker(this);
						cluster.setClusterId(clusterIds[j]);
						newClusters.put(clusterIds[j], cluster);
						if (!addMarkersDynamically || isPositionInVisibleClusters(ms.get(j).getPosition())) {
							refresh(cluster);
						}
					}
					cluster.add(ms.get(j));
					markers.put(ms.get(j), cluster);
				}
			}
		}
		clusters = newClusters;
	}

	private void joinClusters() {
		LongSparseArray<ClusterMarker> newClusters = new LongSparseArray<ClusterMarker>();
		LongSparseArray<List<ClusterMarker>> oldClusters = new LongSparseArray<List<ClusterMarker>>();
		for (int i = 0; i < clusters.size(); i++) {
			ClusterMarker cluster = clusters.valueAt(i);
			List<DelegatingMarker> ms = cluster.getMarkersInternal();
			if (ms.isEmpty()) {
				cluster.removeVirtual();
				continue;
			}
			long clusterId = calculateClusterId(ms.get(0).getPosition());
			List<ClusterMarker> clusterList = oldClusters.get(clusterId);
			if (clusterList == null) {
				clusterList = new ArrayList<ClusterMarker>();
				oldClusters.put(clusterId, clusterList);
			}
			clusterList.add(cluster);
		}
		for (int i = 0; i < oldClusters.size(); i++) {
			long clusterId = oldClusters.keyAt(i);
			List<ClusterMarker> clusterList = oldClusters.valueAt(i);
			if (clusterList.size() == 1) {
				ClusterMarker cluster = clusterList.get(0);
				cluster.setClusterId(clusterId);
				newClusters.put(clusterId, cluster);
			} else {
				ClusterMarker cluster = new ClusterMarker(this);
				cluster.setClusterId(clusterId);
				newClusters.put(clusterId, cluster);
				if (!addMarkersDynamically || isPositionInVisibleClusters(clusterList.get(0).getMarkersInternal().get(0).getPosition())) {
					refresh(cluster);
				}
				for (ClusterMarker old : clusterList) {
					old.removeVirtual();
					List<DelegatingMarker> ms = old.getMarkersInternal();
					for (DelegatingMarker m : ms) {
						cluster.add(m);
						markers.put(m, cluster);
					}
				}
			}
		}
		clusters = newClusters;
	}

	private void addMarkersInVisibleRegion() {
		calculateVisibleClusters();
		for (DelegatingMarker marker : markers.keySet()) {
			LatLng position = marker.getPosition();
			if (isPositionInVisibleClusters(position)) {
				ClusterMarker cluster = markers.get(marker);
				refresh(cluster);
			}
		}
		refresher.refreshAll();
	}

	private void calculateVisibleClusters() {
		IProjection projection = map.getProjection();
		VisibleRegion visibleRegion = projection.getVisibleRegion();
		LatLngBounds bounds = visibleRegion.latLngBounds;
		visibleClusters[0] = convLat(bounds.southwest.latitude);
		visibleClusters[1] = convLng(bounds.southwest.longitude);
		visibleClusters[2] = convLat(bounds.northeast.latitude);
		visibleClusters[3] = convLng(bounds.northeast.longitude);
	}

	private long calculateClusterId(LatLng position) {
		long y = convLat(position.latitude);
		long x = convLng(position.longitude);
		long ret = (y << 32) + x;
		return ret;
	}

	private int convLat(double lat) {
		return (int) (SphericalMercator.scaleLatitude(lat) / clusterSize);
	}

	private int convLng(double lng) {
		return (int) (SphericalMercator.scaleLongitude(lng) / clusterSize);
	}

	private double calculateClusterSize(int zoom) {
		return baseClusterSize / (1 << zoom);
	}

	com.google.android.gms.maps.model.Marker createMarker(int markersCount, LatLng position) {
		MarkerOptions mo = iconDataProvider.getIconData(markersCount);
		return map.addMarker(markerOptions.position(position).icon(mo.getIcon()).anchor(mo.getAnchorU(), mo.getAnchorV()));
	}
}
