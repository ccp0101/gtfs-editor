package models.transit;


import static java.util.Collections.sort;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OrderColumn;
import javax.persistence.Query;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonManagedReference;
import org.hibernate.annotations.Type;

import play.Logger;
import play.db.jpa.Model;
import models.gtfs.GtfsSnapshot;

@JsonIgnoreProperties({"entityId", "persistent"})
@Entity
public class TripPattern extends Model {

    public String name;
    public String headsign;

    @Column(length = 8000,columnDefinition="TEXT")
    public String encodedShape;
    
    @JsonIgnore
    @ManyToOne
    public TripShape shape;

    @ManyToOne
    public Route route;

    @JsonManagedReference
    @OneToMany(cascade = CascadeType.ALL)
    public List<TripPatternStop> patternStops;

    public Boolean longest;

    public Boolean weekday;
    public Boolean saturday;
    public Boolean sunday;

    public Boolean useFrequency;

    public Integer startTime;
    public Integer endTime;

    public Integer headway;

    @JsonCreator
    public static TripPattern factory(long id) {
      return TripPattern.findById(id);
    }

    @JsonCreator
    public static TripPattern factory(String id) {
      return TripPattern.findById(Long.parseLong(id));
    }

    public TripPattern()
    {

    }

    public TripPattern(String name, String headsign, TripShape shape, Route route)
    {
    	this.name = name;
    	this.headsign = headsign;
    	this.shape = shape;
    	this.route = route;
    }
    
    public TripPattern delete() {
    	
    	this.patternStops = new ArrayList<TripPatternStop>();
    	this.save();
        
        List<TripPatternStop> patternStops = TripPatternStop.find("pattern = ?", this).fetch();
        for(TripPatternStop patternStop : patternStops)
        {
            patternStop.delete();
        }
        
        List<StopTime> stopTimes = StopTime.find("trip.pattern = ?", this).fetch();
        for(StopTime stopTime : stopTimes)
        {
            stopTime.delete();
        }

        List<Trip> trips = Trip.find("pattern = ?", this).fetch();
        
        for(Trip trip : trips)
        {
            trip.delete();
        }
        
        return super.delete();
    }
    
    public void resequenceTripStops()
    {
        List<TripPatternStop> stops = TripPatternStop.find("pattern = ? order by stopSequence", this).fetch();

        Integer sequence = 0;
        for(TripPatternStop stop : stops)
        {
                stop.stopSequence = sequence;
                stop.save();

                sequence++;
        }
    }
    
    /**
     * Sort a list of patternstops by stop_sequence
     */
    public static class PatternStopSequenceComparator implements Comparator<TripPatternStop> {
        public int compare(TripPatternStop o1, TripPatternStop o2) {
            
            // nulls last
            if (o1 == null && o2 == null)
                return 0;
            if (o1 == null)
                return 1;
            if (o2 == null)
                return -1;
            
            return o1.stopSequence - o2.stopSequence;
        }
    }
    
    /**
     * Sort a list of StopTimes by StopSequence
     */
    public static class StopTimeSequenceComparator implements Comparator<StopTime> {
        public int compare(StopTime o1, StopTime o2) {
            
            // nulls last
            if (o1 == null && o2 == null)
                return 0;
            if (o1 == null)
                return 1;
            if (o2 == null)
                return -1;
            
            return o1.stopSequence - o2.stopSequence;
        }
    }
    
    /**
     * update the trip pattern stops
     * see extensive discussion in ticket #102
     * basically, we assume only one stop has changed---either it's been removed, added or moved
     * this is consistent with the use of Backbone.save in the UI, and
     * also with the principle of least magic possible
     * of course, we check to ensure that that is the case and fail if it's not
     * this lets us easily detect what has happened simply by length
     * @param tripPattern the trip pattern containing the new patternstops
     */
    public void reconcilePatternStops(TripPattern tripPattern) {        
        long[] originalStopIds = new long[this.patternStops.size()];
        long[] newStopIds = new long[tripPattern.patternStops.size()];
        
        // fill the arrays
        sort(this.patternStops, new PatternStopSequenceComparator());
        
        for (int i = 0; i < originalStopIds.length; i++) {
            originalStopIds[i] = this.patternStops.get(i).stop.id;
        }
        
        sort(tripPattern.patternStops, new PatternStopSequenceComparator());
        
        
        for (int i = 0; i < newStopIds.length; i++) {
            newStopIds[i] = tripPattern.patternStops.get(i).stop.id;
            // while we're at it, repack the patternstops so stop sequences monotonically increment by 1
            // we can't just use the function in tripPattern to do this, because it relies on stops having already been persisted to the database
            tripPattern.patternStops.get(i).stopSequence = i;
        }
        
        /* ADDITIONS */
        if (originalStopIds.length == newStopIds.length - 1) {
            // we have an addition; find it

            int differenceLocation = -1;
            for (int i = 0; i < newStopIds.length; i++) {
                if (differenceLocation != -1) {
                    // we've already found the addition
                    if (i < originalStopIds.length && originalStopIds[i] != newStopIds[i + 1]) {
                        // there's another difference, which we weren't expecting
                        throw new IllegalStateException("Multiple differences found when trying to detect stop addition");
                    }
                }

                // if we've reached where one trip has an extra stop, or if the stops at this position differ
                else if (i == newStopIds.length - 1 || originalStopIds[i] != newStopIds[i]) {
                    // we have found the difference
                    differenceLocation = i;
                }
            }

            // repack stop times
            // this is super-easy; we don't need to remove or add anything, as we don't create stop times for
            // the new stop. We would have no way of knowing which trips stop at the new stop, or at what time.
            // Stop times can be manually created in the timetable editor.

            for (Object t : Trip.find("pattern = ?", this).fetch()) {
                Trip trip = (Trip) t;
                
                Iterator<TripPatternStop> pse = tripPattern.patternStops.listIterator();
                TripPatternStop current;
                
                // sort the stop times by stop sequence
                List<StopTime> stopTimes = trip.getStopTimes();
                sort(stopTimes, new StopTimeSequenceComparator());
                
                for (StopTime st : stopTimes) {
                    current = pse.next();
                    
                    while (!current.stop.id.equals(st.stop.id))
                        current = pse.next();
                    
                    st.stopSequence = current.stopSequence;
                    StopTime.em().merge(st);
                }
            }                
        }
        
        /* DELETIONS */
        else if (originalStopIds.length == newStopIds.length + 1) {
            // we have an deletion; find it
            int differenceLocation = -1;
            for (int i = 0; i < originalStopIds.length; i++) {
                if (differenceLocation != -1) {
                    if (originalStopIds[i] != newStopIds[i - 1]) {
                        // there is another difference, which we were not expecting
                        throw new IllegalStateException("Multiple differences found when trying to detect stop removal");
                    }
                }
                
                // we've reacehd the end and the only difference is length (so the last stop is the different one)
                // or we've found the difference
                else if (i == originalStopIds.length -1 || originalStopIds[i] != newStopIds[i]) {
                    differenceLocation = i;
                }
            }
            
            // renumber the stop sequences
            int i = 0;
            for (TripPatternStop ps : tripPattern.patternStops) {
                ps.stopSequence = i++;
                TripPatternStop.em().merge(ps);
            }
            
            // repack stop times and remove stop times for removed pattern stop
            // this will NPE if the original trip pattern had a null stop sequence, but that's not supposed to happen
            int removedStopSeq = this.patternStops.get(differenceLocation).stopSequence;
            long removedStopId = originalStopIds[differenceLocation];
            
            for (Object t : Trip.find("pattern = ?", this).fetch()) {
                Trip trip = (Trip) t;
                
                // sort the stop times by stop sequence
                List<StopTime> stopTimes = trip.getStopTimes();
                sort(stopTimes, new StopTimeSequenceComparator());

                // remove the stop time we want to remove
                StopTime toRemove = null;
                
                // something to get patternStops
                Iterator<TripPatternStop> pse = tripPattern.patternStops.listIterator();
                
                TripPatternStop current;
                
                for (StopTime st : stopTimes) {
                    if (st.stop.id.equals(removedStopId) && st.stopSequence.equals(removedStopSeq)) {
                        toRemove = st;
                        continue;
                    }
                    
                    // ensure this happens at least once per iteration, in case the same stop occurs twice in a row
                    // that is bad but does happen
                    current = pse.next();
                    
                    // skip skipped patternstops
                    // not every trip has to stop at every stop in a pattern
                    while (!current.stop.id.equals(st.stop.id))
                        current = pse.next();
                    
                    st.stopSequence = current.stopSequence;
                    StopTime.em().merge(st);
                }
                
                toRemove.delete();
            }
        }
        
        /* TRANSPOSITIONS */
        else if (originalStopIds.length == newStopIds.length) {
            // we have a transposition, find it
            int fromLocation = -1;
            int toLocation = -1;
            long affectedStopId = -1;
            
            for (int i = 0; i < newStopIds.length; i++) {
                if (fromLocation == -1 && toLocation == -1) {
                    // we have not yet found the difference
                    if (newStopIds[i] != originalStopIds[i]) {
                        // well, now we have
                        // check if this is an insert (destination) or a delete (origin)
                        if (newStopIds[i + 1] == originalStopIds[i]) {
                            // insertion
                            toLocation = i;
                            affectedStopId = newStopIds[i];
                        }
                        
                        else if (newStopIds[i] == originalStopIds[i + 1]) {
                            // deletion
                            fromLocation = i;
                            affectedStopId = originalStopIds[i];
                        }
                        
                        else {
                            throw new IllegalStateException("Difference does not match signature of simple transposition");
                        }
                    }
                }
                
                else if (fromLocation != -1 && toLocation == -1) {
                    // note that we cannot just check if the new stop ID is the affected stop ID, because the
                    // affected stop may appear in the trip more than once
                    
                    // we know where the stop was moved from, but not to
                    if (newStopIds[i] == originalStopIds[i + 1]) {
                        continue;
                    }
                    
                    else {
                        if (newStopIds[i] == affectedStopId) {
                            toLocation = i;
                        }
                        else {
                            throw new IllegalStateException("Difference affected multiple stops when finding to location");
                        }
                    }
                }
                
                else if (toLocation != -1 && fromLocation == -1) {
                    // we know where to but not where from
                    if (newStopIds[i] == originalStopIds[i - 1]){
                        continue;
                    }
                    else {
                        if (newStopIds[i] == affectedStopId) {
                            fromLocation = i;
                        }
                        else {
                            throw new IllegalStateException("Difference affected multiple stops when finding from location");
                        }
                    }
                }
            }
            
            if (fromLocation == -1 && toLocation == -1) {
                // trip pattern did not change, so our work here is done
                return;
            }
            
            TripPatternStop originalPatternStop = this.patternStops.get(fromLocation);
            
            for (Object t : Trip.find("pattern = ?", this).fetch()) {
                Trip trip = (Trip) t;
                
                Iterator<TripPatternStop> pse = tripPattern.patternStops.listIterator();
                TripPatternStop current;
                
                // sort the stop times by stop sequence
                List<StopTime> stopTimes = trip.getStopTimes();
                sort(stopTimes, new StopTimeSequenceComparator());
                
                for (StopTime st : stopTimes) {
                    if (st.stop.id.equals(originalPatternStop.stop.id) &&
                            st.stopSequence.equals(originalPatternStop.stopSequence)) {
                        // this is the moved stop
                        st.stopSequence = toLocation;
                    }
                    else {
                        // this is not the moved stop
                        current = pse.next();
                        
                        
                        // skip skipped stops, also the destination pattern stop
                        while (!current.stop.id.equals(st.stop.id) || current.stopSequence.equals(toLocation))
                            current = pse.next();
                        
                        st.stopSequence = current.stopSequence;
                        StopTime.em().merge(st);
                    }
                }
            }
        }
        
        /* OTHER STUFF IS NOT SUPPORTED */
        else {
            throw new IllegalStateException("Changes to trip pattern stops must be made one at a time");
        }
    }

    public static BigInteger createFromTrip(EntityManager em, BigInteger tripId)
    {
    	Trip trip = Trip.findById(tripId.longValue());

    	Query idQuery = em.createNativeQuery("SELECT NEXTVAL('hibernate_sequence');");
		BigInteger tripPatternId = (BigInteger)idQuery.getSingleResult();

		Query q;

		if(trip.shape != null)
			q = em.createNativeQuery("INSERT INTO trippattern (id, name, route_id, headsign, shape_id)" +
	    	"  VALUES(?, ?, ?, ?, ?);");
		else
			q = em.createNativeQuery("INSERT INTO trippattern (id, name, route_id, headsign)" +
			    	"  VALUES(?, ?, ?, ?);");

	      q.setParameter(1,  tripPatternId.longValue())
	      .setParameter(2,  trip.route.routeShortName + "(" + trip.tripHeadsign + ")")
	      .setParameter(3,  trip.route.id)
	      .setParameter(4,  trip.tripHeadsign);

	      if(trip.shape != null)
	       q.setParameter(5,  trip.shape.id);

	      q.executeUpdate();


    	ArrayList<StopTime> stopTimes = trip.getStopTimes();

    	Integer previousDepartureTime = 0;
    	Double previousDistance = new Double(0);

    	Boolean firstStop = true;
    	
    	String firstStopName = null;
    	String lastStopName = null;

    	for(StopTime stopTime : stopTimes)
    	{
    		BigInteger tripPatternStopId = (BigInteger)idQuery.getSingleResult();

    		q = em.createNativeQuery("INSERT INTO trippatternstop (id, pattern_id, stop_id, stopsequence, defaultdwelltime, defaultdistance, defaulttraveltime)" +
	    	"  VALUES(?, ?, ?, ?, ?, ?, ?);");

    		q.setParameter(1,  tripPatternStopId.longValue());
    		q.setParameter(2,  tripPatternId.longValue());
    		q.setParameter(3,  stopTime.stop.id);
    		q.setParameter(4,  stopTime.stopSequence);
    		q.setParameter(5,  stopTime.departureTime - stopTime.arrivalTime);

    		if(firstStop)
    		{
    			previousDepartureTime = stopTime.departureTime;

    			q.setParameter(6,  new Double(0));
    			q.setParameter(7,  0);

    			firstStop = false;
    			
    			firstStopName = stopTime.stop.stopName;
    		}
    		else
    		{
    			q.setParameter(6,  stopTime.shapeDistTraveled - previousDistance);
    			q.setParameter(7,  stopTime.arrivalTime - previousDepartureTime);
    	

    			previousDepartureTime = stopTime.departureTime;
    			previousDistance = stopTime.shapeDistTraveled;
    		}
    		
    		lastStopName = stopTime.stop.stopName;

    		q.executeUpdate();
    		
    		q = em.createNativeQuery("INSERT INTO trippattern_trippatternstop (trippattern_id, patternstops_id) VALUES (?, ?);");
    		
    		q.setParameter(1,  tripPatternId.longValue());
    		q.setParameter(2,  tripPatternStopId.longValue());
    		
    		q.executeUpdate();
    		
    				
    				
    	}
    	
    	trip.route.routeLongName = firstStopName + " - " + lastStopName;
    	trip.route.save();

    	Logger.info("Adding trip pattern: " + trip.route.routeShortName + " (" + trip.tripHeadsign + ")");

    	return tripPatternId;
    }
}
