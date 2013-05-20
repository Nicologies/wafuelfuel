package com.ezhang.pop.ui;

import java.util.*;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.ezhang.pop.core.LocationService;
import com.ezhang.pop.core.LocationSpliter;
import com.ezhang.pop.core.StateMachine;
import com.ezhang.pop.core.StateMachine.EventAction;
import com.ezhang.pop.model.*;
import com.ezhang.pop.rest.PopRequestFactory;
import com.ezhang.pop.rest.PopRequestManager;
import com.ezhang.pop.settings.AppSettings;
import com.foxykeep.datadroid.requestmanager.Request;
import com.foxykeep.datadroid.requestmanager.RequestManager.RequestListener;

public class FuelStateMachine extends Observable implements RequestListener {

    private static class TimerHandler extends Handler {
		FuelStateMachine m_fuelStateMachine = null;

		public TimerHandler(FuelStateMachine fuelStateMachine) {
			m_fuelStateMachine = fuelStateMachine;
		}

		@Override
		public void handleMessage(Message msg) {
			m_fuelStateMachine.m_stateMachine
					.HandleEvent(EmEvent.Timeout, null);
		}
	}

	enum EmState {
		Start, GeoLocationReceived, SuburbReceived, FuelInfoReceived, DistanceReceived, Timeout
	}

	enum EmEvent {
		Invalid, GeoLocationEvent, SuburbEvent, FuelInfoEvent, DistanceEvent, Refresh, Timeout, RecalculatePrice
	}

	StateMachine<EmState, EmEvent> m_stateMachine = new StateMachine<EmState, EmEvent>(
			EmState.Start);
	public ArrayList<FuelDistanceItem> m_fuelDistanceItems = new ArrayList<FuelDistanceItem>();
	private List<FuelInfo> m_fuelInfoList = null;
	private PopRequestManager m_restReqManager;
	private LocationManager m_locationManager;
	public Location m_location = null;
	public String m_suburb = null;
	public String m_address = null;
	private String m_provider = null;
	public EmEvent m_timeoutEvent = EmEvent.Invalid;
	Timer m_timer = null;
	TimerTask m_timerTask = null;
	Handler m_timeoutHandler = new TimerHandler(this);
	AppSettings m_settings = null;
	boolean m_enableGPS = true;

    FuelInfoCache m_fuelInfoCache = new FuelInfoCache();
    FuelDistanceInfoCache m_fuelDistanceCached = new FuelDistanceInfoCache();

	public FuelStateMachine(PopRequestManager reqManager,
			LocationManager locationManager, AppSettings settings,
			boolean useGPSAsLocation) {
		m_restReqManager = reqManager;
		m_locationManager = locationManager;
		m_settings = settings;

		InitStateMachineTransitions();

		m_enableGPS = useGPSAsLocation;
		ToggleGPS(useGPSAsLocation);

		Refresh();
	}

	public void ToggleGPS(boolean toggleOn) {
		if (toggleOn) {
			m_provider = LocationService.GetBestProvider(m_locationManager);
			if (m_provider != null) {
				m_locationManager.requestLocationUpdates(m_provider,
						60 * 1000L, 20.0f, this.m_locationListener);
			}

			this.m_location = this.m_locationManager
					.getLastKnownLocation(m_provider);
		} else {
			m_provider = null;
			m_locationManager.removeUpdates(this.m_locationListener);
		}
		m_enableGPS = toggleOn;

		if (!m_enableGPS) {
			m_address = this.m_settings.GetHistoryLocations().get(0);
			m_suburb = LocationSpliter.Split(m_address).second;
			this.m_stateMachine.SetState(EmState.SuburbReceived);
			this.Notify();
		}
	}

	public void Refresh() {
		this.m_stateMachine.HandleEvent(EmEvent.Refresh, null);
	}

	private void StartTimer(int millSeconds) {
		StopTimer();

		if (m_timer == null) {
			m_timer = new Timer();
		}

		if (m_timerTask == null) {
			m_timerTask = new TimerTask() {
				public void run() {
					m_timeoutHandler.sendMessage(new Message());
				}
			};
		}

		if (m_timer != null && m_timerTask != null) {
			m_timer.schedule(m_timerTask, millSeconds);
		}
	}

	private void StopTimer() {
		if (m_timer != null) {
			m_timer.cancel();
			m_timer = null;
		}

		if (m_timerTask != null) {
			m_timerTask.cancel();
			m_timerTask = null;
		}
	}

	private void InitStateMachineTransitions() {
		m_stateMachine.AddTransition(EmState.Start,
				EmState.GeoLocationReceived, EmEvent.GeoLocationEvent,
				new EventAction() {
					public void PerformAction(Bundle param) {
						RequestSuburb();
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.Start, EmState.Start,
				EmEvent.Refresh, new EventAction() {
					public void PerformAction(Bundle param) {
						Start();
					}
				});

		m_stateMachine.AddTransition(EmState.Start, EmState.Timeout,
				EmEvent.Timeout, new EventAction() {
					public void PerformAction(Bundle param) {
						m_timeoutEvent = EmEvent.GeoLocationEvent;
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.Timeout, EmState.Start,
				EmEvent.Refresh, new EventAction() {
					public void PerformAction(Bundle param) {
						Start();
					}
				});

		m_stateMachine.AddTransition(EmState.GeoLocationReceived,
				EmState.SuburbReceived, EmEvent.SuburbEvent, new EventAction() {
					public void PerformAction(Bundle param) {
						m_suburb = param
								.getString(PopRequestFactory.BUNDLE_CUR_SUBURB_DATA);
						m_address = param
								.getString(PopRequestFactory.BUNDLE_CUR_ADDRESS_DATA);
						if (m_suburb == "") {
							m_stateMachine
									.SetState(EmState.GeoLocationReceived);
							Notify();
							RequestSuburb();
							return;
						}
						Notify();
						RequestFuelInfo();
					}
				});

		m_stateMachine.AddTransition(EmState.GeoLocationReceived,
				EmState.Timeout, EmEvent.Timeout, new EventAction() {
					public void PerformAction(Bundle param) {
						m_timeoutEvent = EmEvent.SuburbEvent;
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.GeoLocationReceived,
				EmState.GeoLocationReceived, EmEvent.Refresh,
				new EventAction() {
					public void PerformAction(Bundle param) {
						if (m_suburb == null) {
							RequestSuburb();
							Notify();
						} else {
							m_stateMachine.SetState(EmState.SuburbReceived);
							Notify();
							RequestFuelInfo();
						}
					}
				});

		m_stateMachine.AddTransition(EmState.SuburbReceived,
				EmState.FuelInfoReceived, EmEvent.FuelInfoEvent,
				new EventAction() {
					public void PerformAction(Bundle param) {
                        List<FuelInfo> fuelInfo = param
                                .getParcelableArrayList(PopRequestFactory.BUNDLE_FUEL_DATA);
                        OnFuelInfoReceived(fuelInfo);
					}
				});

		m_stateMachine.AddTransition(EmState.SuburbReceived,
				EmState.SuburbReceived, EmEvent.Refresh, new EventAction() {
					public void PerformAction(Bundle param) {
						RequestFuelInfo();
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.SuburbReceived,
				EmState.GeoLocationReceived, EmEvent.GeoLocationEvent,
				new EventAction() {
					public void PerformAction(Bundle param) {
						RequestSuburb();
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.SuburbReceived, EmState.Timeout,
				EmEvent.Timeout, new EventAction() {
					public void PerformAction(Bundle param) {
						m_timeoutEvent = EmEvent.FuelInfoEvent;
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.FuelInfoReceived,
				EmState.DistanceReceived, EmEvent.DistanceEvent,
				new EventAction() {
					public void PerformAction(Bundle param) {
						StopTimer();
						DistanceMatrix distanceMatrix = param
								.getParcelable(PopRequestFactory.BUNDLE_DISTANCE_MATRIX_DATA);
						OnDistanceMatrixReceived(distanceMatrix);
						Notify();
					}
				});
		m_stateMachine.AddTransition(EmState.FuelInfoReceived,
				EmState.SuburbReceived, EmEvent.Refresh, new EventAction() {
					public void PerformAction(Bundle param) {
						RequestFuelInfo();
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.FuelInfoReceived,
				EmState.GeoLocationReceived, EmEvent.GeoLocationEvent,
				new EventAction() {
					public void PerformAction(Bundle param) {
						RequestSuburb();
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.FuelInfoReceived, EmState.Timeout,
				EmEvent.Timeout, new EventAction() {
					public void PerformAction(Bundle param) {
						m_timeoutEvent = EmEvent.DistanceEvent;
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.DistanceReceived,
				EmState.GeoLocationReceived, EmEvent.GeoLocationEvent,
				new EventAction() {
					public void PerformAction(Bundle param) {
						RequestSuburb();
						Notify();
					}
				});

		m_stateMachine.AddTransition(EmState.DistanceReceived,
				EmState.SuburbReceived, EmEvent.Refresh, new EventAction() {
					public void PerformAction(Bundle param) {
						RequestFuelInfo();
						Notify();
					}
				});
		m_stateMachine.AddTransition(EmState.DistanceReceived,
				EmState.DistanceReceived, EmEvent.RecalculatePrice,
				new EventAction() {
					public void PerformAction(Bundle param) {
						OnRecalculatePrice();
						Notify();
					}
				});
	}

    private void OnFuelInfoReceived(List<FuelInfo> fuelInfoList) {
        m_fuelInfoList = fuelInfoList;
        if (m_fuelInfoList.size() != 0) {
            RequestDistanceMatrix(m_fuelInfoList);
        } else {
            m_stateMachine.SetState(EmState.DistanceReceived);
        }

        m_fuelInfoCache.CacheFuelInfo(m_fuelInfoList);
        Notify();
    }

    private void OnRecalculatePrice() {
		for (FuelDistanceItem item : this.m_fuelDistanceItems) {
			if (item.voucherType != null && item.voucherType != "") {
				if (item.voucherType == "wws") {
					if (m_settings.m_wwsDiscount != item.voucher) {
						item.price += item.voucher - m_settings.m_wwsDiscount;
						item.voucher = m_settings.m_wwsDiscount;
					}
				}
				if (item.voucherType == "coles") {
					if (m_settings.m_colesDiscount != item.voucher) {
						item.price += item.voucher - m_settings.m_colesDiscount;
						item.voucher = m_settings.m_colesDiscount;
					}
				}
			}
		}
	}

	private void OnDistanceMatrixReceived(DistanceMatrix distanceMatrix) {
		m_fuelDistanceItems.clear();
		int i = 0;
		for (DistanceMatrixItem distanceItem : distanceMatrix
				.GetDistanceItems()) {
			FuelDistanceItem item = new FuelDistanceItem();
			item.distance = distanceItem.distance;
			item.distanceValue = distanceItem.distanceValue;
			item.duration = distanceItem.duration;
			FuelInfo fuelInfo = this.m_fuelInfoList.get(i);
			item.tradingName = fuelInfo.tradingName;
			item.price = fuelInfo.price;
			String lowTradingName = item.tradingName
					.toLowerCase(Locale.ENGLISH);
			if (lowTradingName.contains("woolworths")
					&& m_settings.m_wwsDiscount > 0) {
				item.price -= m_settings.m_wwsDiscount;
				item.voucher = m_settings.m_wwsDiscount;
				item.voucherType = "wws";
			} else if (lowTradingName.contains("coles")
					&& m_settings.m_colesDiscount > 0) {
				item.price -= m_settings.m_colesDiscount;
				item.voucher = m_settings.m_colesDiscount;
				item.voucherType = "coles";
			} else {
				item.voucherType = "";
			}
			item.latitude = fuelInfo.latitude;
			item.longitude = fuelInfo.longitude;
			item.destinationAddr = fuelInfo.GetAddress();
			m_fuelDistanceItems.add(item);
			i++;
		}
        m_fuelDistanceCached.CacheFuelInfo(m_fuelDistanceItems);
		Notify();
	}

	@Override
	public void onRequestFinished(Request request, Bundle resultData) {
		if (request.getRequestType() == PopRequestFactory.REQ_TYPE_DISTANCE_MATRIX) {
			this.m_stateMachine.HandleEvent(EmEvent.DistanceEvent, resultData);
		}

		if (request.getRequestType() == PopRequestFactory.REQ_TYPE_GET_CUR_SUBURB) {
			this.m_stateMachine.HandleEvent(EmEvent.SuburbEvent, resultData);
		}

		if (request.getRequestType() == PopRequestFactory.REQ_TYPE_FUEL) {
			this.m_stateMachine.HandleEvent(EmEvent.FuelInfoEvent, resultData);
		}
	}

	@Override
	public void onRequestConnectionError(Request request, int statusCode) {
	}

	@Override
	public void onRequestDataError(Request request) {
	}

	@Override
	public void onRequestCustomError(Request request, Bundle resultData) {
	}

	private void RequestSuburb() {
		StartTimer(5000);
		Request req = PopRequestFactory.GetCurrentSuburbRequest(m_location);
		m_restReqManager.execute(req, this);
	}

	private void RequestFuelInfo() {
        if(m_fuelInfoCache.HitCache(m_settings, m_suburb)){
            m_stateMachine.SetState(EmState.FuelInfoReceived);
            OnFuelInfoReceived(m_fuelInfoCache.m_cachedFuelInfo);
            Notify();
            return;
        }
		StartTimer(5000);
		Request req = PopRequestFactory.GetFuelInfoRequest(m_suburb,
				m_settings.IncludeSurroundings(), m_settings.GetFuelType());
        m_fuelInfoCache.SetCacheContext(m_settings, m_suburb);
		m_restReqManager.execute(req, this);
	}

	private void RequestDistanceMatrix(List<FuelInfo> fuelInfoList) {
        if (m_fuelDistanceCached.HitCache(m_settings, m_suburb, m_address)){
            m_stateMachine.SetState(EmState.DistanceReceived);
            Notify();
            return;
        }

		DestinationList destinations = new DestinationList();
		for (FuelInfo item : fuelInfoList) {
			destinations.AddDestination(item.latitude, item.longitude);
		}
		
		String src = "";
		
		if(this.m_enableGPS){
            if (m_location == null) {
                m_location = this.m_locationManager
                        .getLastKnownLocation(m_provider);
            }
			if (m_location != null){			
				src = String.format("%s,%s", this.m_location.getLatitude(),
					this.m_location.getLongitude());
			}
		}else{
			src = this.m_address.replace(' ', '+');
		}
		StartTimer(5000);
        m_fuelDistanceCached.SetCacheContext(m_settings, m_suburb, m_address);
		Request req = PopRequestFactory
				.GetDistanceMatrixRequest(src, destinations);
		m_restReqManager.execute(req, this);			
	}

	private final LocationListener m_locationListener = new LocationListener() {
		public void onLocationChanged(Location location) {
			if (!m_enableGPS) {
				return;
			}

			// log it when the location changes
			if (location != null) {
				if (m_location == null || !location.equals(m_location)) {
					m_location = location;
					m_suburb = null;
					m_fuelInfoList = null;
					m_fuelDistanceItems.clear();
					m_stateMachine.HandleEvent(EmEvent.GeoLocationEvent, null);
				}
			}
		}

		public void onProviderDisabled(String provider) {
			// Provider��disableʱ�����˺������GPS���ر�
		}

		public void onProviderEnabled(String provider) {
			// Provider��enableʱ�����˺������GPS����
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			// Provider��ת̬�ڿ��á���ʱ�����ú��޷������״ֱ̬���л�ʱ�����˺���
		}
	};

	public EmState GetCurState() {
		// TODO Auto-generated method stub
		return this.m_stateMachine.GetState();
	}

	private void Notify() {
		setChanged();
		notifyObservers();
	}

	private void Start() {
		if (m_location == null) {
			StartTimer(3 * 60 * 1000);
			Notify();
			return;
		}
		if (m_suburb == null) {
			m_stateMachine.SetState(EmState.GeoLocationReceived);
			Notify();
			RequestSuburb();
		} else {
			m_stateMachine.SetState(EmState.SuburbReceived);
			Notify();
			RequestFuelInfo();
		}
	}

	public void ReCalculatePrice() {
		this.m_stateMachine.HandleEvent(EmEvent.RecalculatePrice, null);
	}
}
