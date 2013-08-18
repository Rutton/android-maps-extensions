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

import pl.mg6.android.maps.extensions.ClusterOptions;
import pl.mg6.android.maps.extensions.ClusterOptionsProvider;
import pl.mg6.android.maps.extensions.ClusteringSettings;
import pl.mg6.android.maps.extensions.ClusteringSettings.IconDataProvider;
import pl.mg6.android.maps.extensions.Marker;
import pl.mg6.android.maps.extensions.utils.SphericalMercator;

import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.VisibleRegion;

import android.util.Log;

class GridClusteringStrategy implements ClusteringStrategy {

	private static final boolean DEBUG_GRID = false;

    private final ClusteringSettings.ClusteringChangeListener clusteringListener;

    private DebugHelper debugHelper;

	private final MarkerOptions markerOptions = new MarkerOptions();

	private boolean addMarkersDynamically;
	private double baseClusterSize;
    private boolean useLeaderPosition;
	private IGoogleMap map;
	private Map<DelegatingMarker, ClusterMarker> markers;
	private double clusterSize;
	private int oldZoom, zoom;
	private int[] visibleClusters = new int[4];

	private Map<ClusterKey, ClusterMarker> clusters = new HashMap<ClusterKey, ClusterMarker>();

	private ClusterRefresher refresher;
	private ClusterOptionsProvider clusterOptionsProvider;
	private IconDataProvider iconDataProvider;

	public GridClusteringStrategy(ClusteringSettings settings, IGoogleMap map, List<DelegatingMarker> markers, ClusterRefresher refresher) {
		this.clusterOptionsProvider = settings.getClusterOptionsProvider();
		this.iconDataProvider = settings.getIconDataProvider();
		this.addMarkersDynamically = settings.isAddMarkersDynamically();
		this.baseClusterSize = settings.getClusterSize();
        this.useLeaderPosition = settings.isUseLeaderPosition();
        this.clusteringListener = settings.getClusteringListener();
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
		for (ClusterMarker cluster : clusters.values()) {
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
    public void onClusterGroupChange(DelegatingMarker marker) {
        updateWithMarker(marker);
    }

    private void updateWithMarker(DelegatingMarker marker) {
        if (!marker.isVisible()) {
            return;
        }
        ClusterMarker oldCluster = markers.get(marker);
        boolean isLeader = false;
        if (oldCluster != null) {
            isLeader = marker == oldCluster.getLeadingMarker();
            oldCluster.remove(marker);
            refresh(oldCluster);
        }
        int newIndex = addMarker(marker);

        if (useLeaderPosition) {
            ClusterMarker newCluster = markers.get(marker);
            if (oldCluster == newCluster && isLeader) {
                newCluster.setLeadingPosition(newIndex);
            }
        }
    }

	@Override
	public void onAdd(DelegatingMarker marker) {
		if (!marker.isVisible()) {
			return;
		}
		addMarker(marker);
	}

	private int addMarker(DelegatingMarker marker) {
		LatLng position = marker.getPosition();
		ClusterKey key = calculateClusterKey(marker.getClusterGroup(), position);
		ClusterMarker cluster = findClusterById(key);
		cluster.add(marker);
		markers.put(marker, cluster);
		if (!addMarkersDynamically || isPositionInVisibleClusters(position)) {
			refresh(cluster);
		}

        return cluster.size() - 1;
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
        boolean isLeadingMarker = marker == cluster.getLeadingMarker();
		if (cluster != null) {
			cluster.remove(marker);
			refresh(cluster);
            if (isLeadingMarker) {
                cluster.setLeadingPosition(0);
            }
		}
	}

	@Override
	public void onPositionChange(DelegatingMarker marker) {
        updateWithMarker(marker);
	}

	@Override
	public Marker map(com.google.android.gms.maps.model.Marker original) {
		for (ClusterMarker cluster : clusters.values()) {
			if (original.equals(cluster.getVirtual())) {
				return cluster;
			}
		}
		return null;
	}

	@Override
	public List<Marker> getDisplayedMarkers() {
		List<Marker> displayedMarkers = new ArrayList<Marker>();
		for (ClusterMarker cluster : clusters.values()) {
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

	private ClusterMarker findClusterById(ClusterKey key) {
		ClusterMarker cluster = clusters.get(key);
		if (cluster == null) {
			cluster = new ClusterMarker(this);
			clusters.put(key, cluster);
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
		Map<ClusterKey, ClusterMarker> newClusters = new HashMap<ClusterKey, ClusterMarker>();
		for (ClusterMarker cluster : clusters.values()) {
			List<DelegatingMarker> ms = cluster.getMarkersInternal();
			if (ms.isEmpty()) {
				cluster.removeVirtual();
				continue;
			}
			ClusterKey[] clusterIds = new ClusterKey[ms.size()];
            DelegatingMarker leadingMarker = cluster.getLeadingMarker();
            ClusterKey leadingClusterId = calculateClusterKey(leadingMarker.getClusterGroup(), leadingMarker.getPosition());

			boolean allSame = true;
			for (int j = 0; j < ms.size(); j++) {
				clusterIds[j] = calculateClusterKey(ms.get(j).getClusterGroup(), ms.get(j).getPosition());
				if (!clusterIds[j].equals(leadingClusterId)) {
					allSame = false;
				}
			}
			if (allSame) {
				newClusters.put(leadingClusterId, cluster);
			} else {
				cluster.removeVirtual();
				for (int j = 0; j < ms.size(); j++) {
					cluster = newClusters.get(clusterIds[j]);
					if (cluster == null) {
						cluster = new ClusterMarker(this);
                        if (useLeaderPosition) {
                            cluster.setLeadingPosition(0);
                        }
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
		Map<ClusterKey, ClusterMarker> newClusters = new HashMap<ClusterKey, ClusterMarker>();
		Map<ClusterKey, List<ClusterMarker>> oldClusters = new HashMap<ClusterKey, List<ClusterMarker>>();
		for (ClusterMarker cluster : clusters.values()) {
			List<DelegatingMarker> ms = cluster.getMarkersInternal();
			if (ms.isEmpty()) {
				cluster.removeVirtual();
				continue;
			}

            DelegatingMarker leaderMarker = cluster.getLeadingMarker();
			ClusterKey clusterId = calculateClusterKey(leaderMarker.getClusterGroup(), leaderMarker.getPosition());
			List<ClusterMarker> clusterList = oldClusters.get(clusterId);
			if (clusterList == null) {
				clusterList = new ArrayList<ClusterMarker>();
				oldClusters.put(clusterId, clusterList);
			}
			clusterList.add(cluster);
		}
		for (ClusterKey key : oldClusters.keySet()) {
			List<ClusterMarker> clusterList = oldClusters.get(key);
			if (clusterList.size() == 1) {
				ClusterMarker cluster = clusterList.get(0);
				newClusters.put(key, cluster);
			} else {
				ClusterMarker cluster = new ClusterMarker(this);
				newClusters.put(key, cluster);
				if (!addMarkersDynamically || isPositionInVisibleClusters(clusterList.get(0).getLeadingMarker().getPosition())) {
					refresh(cluster);
				}

                int clusterMaxSizeSoFar = 0;
                boolean hasMoreMarkers = false;
                for (ClusterMarker old : clusterList) {
                    List<Marker> markersInCluster = old.getMarkers();
                    DelegatingMarker newLeadingMarker = null;
                    if ( useLeaderPosition && markersInCluster.size() > clusterMaxSizeSoFar) {
                        clusterMaxSizeSoFar = markersInCluster.size();
                        newLeadingMarker = old.getLeadingMarker();
                        hasMoreMarkers = true;
                    }
					old.removeVirtual();
					List<DelegatingMarker> ms = old.getMarkersInternal();
					for (DelegatingMarker m : ms) {
						cluster.add(m);
						markers.put(m, cluster);
					}

                    if (useLeaderPosition && hasMoreMarkers) {
                        cluster.setLeadingMarker(newLeadingMarker);
                        hasMoreMarkers = false;
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

	private ClusterKey calculateClusterKey(int group, LatLng position) {
		int y = convLat(position.latitude);
		int x = convLng(position.longitude);
		return new ClusterKey(group, y, x);
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

	com.google.android.gms.maps.model.Marker createMarker(List<Marker> markers, LatLng position) {
		if (clusterOptionsProvider != null) {
			ClusterOptions opts = clusterOptionsProvider.getClusterOptions(markers);
			return map.addMarker(markerOptions.position(position).icon(opts.getIcon()).anchor(opts.getAnchorU(), opts.getAnchorV()));
		}
		if (iconDataProvider != null) {
			MarkerOptions opts = iconDataProvider.getIconData(markers.size());
			return map.addMarker(markerOptions.position(position).icon(opts.getIcon()).anchor(opts.getAnchorU(), opts.getAnchorV()));
		}
		throw new RuntimeException();
	}

	private static class ClusterKey {
		private final int group;
		private final int latitudeId;
		private final int longitudeId;

		private ClusterKey(int group, int latitudeId, int longitudeId) {
			this.group = group;
			this.latitudeId = latitudeId;
			this.longitudeId = longitudeId;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}

			ClusterKey that = (ClusterKey) o;

			if (group != that.group) {
				return false;
			}
			if (latitudeId != that.latitudeId) {
				return false;
			}
			if (longitudeId != that.longitudeId) {
				return false;
			}
			return true;
		}

		@Override
		public int hashCode() {
			int result = group;
			result = 31 * result + latitudeId;
			result = 31 * result + longitudeId;
			return result;
		}
	}
}
