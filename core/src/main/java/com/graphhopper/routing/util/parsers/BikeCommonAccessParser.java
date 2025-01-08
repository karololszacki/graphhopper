package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.util.FerrySpeedCalculator;
import com.graphhopper.routing.util.WayAccess;
import com.graphhopper.routing.util.TransportationMode;

import java.util.*;

import static com.graphhopper.routing.util.parsers.OSMTemporalAccessParser.hasTemporalRestriction;

public abstract class BikeCommonAccessParser extends AbstractAccessParser implements TagParser {

    private static final Set<String> OPP_LANES = new HashSet<>(Arrays.asList("opposite", "opposite_lane", "opposite_track"));
    private final Set<String> allowedHighways = new HashSet<>();
    private final BooleanEncodedValue roundaboutEnc;

    protected BikeCommonAccessParser(BooleanEncodedValue accessEnc, BooleanEncodedValue roundaboutEnc) {
        super(accessEnc, OSMRoadAccessParser.toOSMRestrictions(TransportationMode.BIKE));

        this.roundaboutEnc = roundaboutEnc;

        restrictedValues.add("agricultural");
        restrictedValues.add("forestry");
        restrictedValues.add("delivery");

        barriers.add("fence");

        allowedHighways.addAll(Arrays.asList("living_street", "steps", "cycleway", "path", "footway", "platform",
                "pedestrian", "track", "service", "residential", "unclassified", "road", "bridleway",
                "motorway", "motorway_link", "trunk", "trunk_link",
                "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"));
    }

    public WayAccess getAccess(ReaderWay way) {
        String highwayValue = way.getTag("highway");
        if (highwayValue == null) {
            return checkNonHighwayAccess(way);
        }

        if (!allowedHighways.contains(highwayValue))
            return WayAccess.CAN_SKIP;

        String bicycleValue = way.getTag("bicycle");
        boolean hasAllowedBikesExplicitly = intendedValues.contains(bicycleValue);

        // use the way if it is tagged for bikes, even if for dismount (speed-wise: PUSHING_SECTION_SPEED)
        if ("dismount".equals(bicycleValue))
            return WayAccess.WAY;

        // check tricky ways, that DO NOT have explicit tags like bicycle=yes
        if (!hasAllowedBikesExplicitly) {
            if ("motorway".equals(highwayValue) || "motorway_link".equals(highwayValue))
                return WayAccess.CAN_SKIP;

            if (way.hasTag("motorroad", "yes"))
                return WayAccess.CAN_SKIP;

            if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford")))
                return WayAccess.CAN_SKIP;
        }

        // check explicit access restrictions
        WayAccess explicitRestriction = checkExplicitRestrictionsHierarchy(way);
        if (explicitRestriction != WayAccess.OTHER) {
            return explicitRestriction;
        }

        return WayAccess.WAY;
    }

    private WayAccess checkNonHighwayAccess(ReaderWay way) {
        // special case not for all acceptedRailways, only platform
        if (way.hasTag("railway", "platform"))
            return WayAccess.WAY;

        if (way.hasTag("man_made", "pier"))
            return WayAccess.WAY;


        WayAccess explicitRestriction = checkExplicitRestrictionsHierarchy(way);
        if (explicitRestriction != WayAccess.CAN_SKIP) { // either WAY or OTHER (undetermined)
            return FerrySpeedCalculator.isFerry(way) ? WayAccess.FERRY : WayAccess.WAY;
        }

        // non-highway, no special exceptions? default: skip!
        return WayAccess.CAN_SKIP;
    }

    /**
     * Checks if access is explicitly restricted or permitted
     *
     * @param way way to be checked (you might also need to do isFerry() check)
     * @return WayAccess whether CAN_SKIP, WAY, or OTHER (not found any explicit restriction/allowance)
     */
    private WayAccess checkExplicitRestrictionsHierarchy(ReaderWay way) {
        // go over restrictionKeys; important: they must be defined in the order of most to least specific
        // hierarchy reference: https://wiki.openstreetmap.org/wiki/Key:access#Land-based_transportation
        for (String key : restrictionKeys) {
            String value = way.getTag(key);
            if (value == null) {
                continue; // not found, go to next key
            }

            // `;` can be an access delimiter; https://github.com/graphhopper/graphhopper/pull/2676
            String[] split = value.split(";");
            for (String val : split) {
                if (restrictedValues.contains(val)) {
                    return WayAccess.CAN_SKIP; // explicit ban!
                }
                if (intendedValues.contains(val)) {
                    return WayAccess.WAY; // explicit allow!
                }
            }
        }

        // none of restricted, or intended values found; indicate this way needs further processing by using OTHER
        return WayAccess.OTHER;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
        WayAccess access = getAccess(way);
        if (access.canSkip())
            return;

        if (access.isFerry()) {
            accessEnc.setBool(false, edgeId, edgeIntAccess, true);
            accessEnc.setBool(true, edgeId, edgeIntAccess, true);
        } else {
            handleAccess(edgeId, edgeIntAccess, way);
        }

        if (way.hasTag("gh:barrier_edge")) {
            List<Map<String, Object>> nodeTags = way.getTag("node_tags", Collections.emptyList());
            handleBarrierEdge(edgeId, edgeIntAccess, nodeTags.get(0));
        }
    }

    protected void handleAccess(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
        // handle oneways. The value -1 means it is a oneway but for reverse direction of stored geometry.
        // The tagging oneway:bicycle=no or cycleway:right:oneway=no or cycleway:left:oneway=no lifts the generic oneway restriction of the way for bike
        boolean isOneway = way.hasTag("oneway", ONEWAYS) && !way.hasTag("oneway", "-1") && !way.hasTag("bicycle:backward", intendedValues)
                || way.hasTag("oneway", "-1") && !way.hasTag("bicycle:forward", intendedValues)
                || way.hasTag("oneway:bicycle", ONEWAYS)
                || way.hasTag("cycleway:left:oneway", ONEWAYS)
                || way.hasTag("cycleway:right:oneway", ONEWAYS)
                || way.hasTag("vehicle:backward", restrictedValues) && !way.hasTag("bicycle:forward", intendedValues)
                || way.hasTag("vehicle:forward", restrictedValues) && !way.hasTag("bicycle:backward", intendedValues)
                || way.hasTag("bicycle:forward", restrictedValues)
                || way.hasTag("bicycle:backward", restrictedValues);

        if ((isOneway || roundaboutEnc.getBool(false, edgeId, edgeIntAccess))
                && !way.hasTag("oneway:bicycle", "no")
                && !(way.hasTag("cycleway:both") && !way.hasTag("cycleway:both", "no"))
                && !way.hasTag("cycleway", OPP_LANES)
                && !way.hasTag("cycleway:left", OPP_LANES)
                && !way.hasTag("cycleway:right", OPP_LANES)
                && !way.hasTag("cycleway:left:oneway", "no")
                && !way.hasTag("cycleway:right:oneway", "no")) {
            boolean isBackward = way.hasTag("oneway", "-1")
                    || way.hasTag("oneway:bicycle", "-1")
                    || way.hasTag("cycleway:left:oneway", "-1")
                    || way.hasTag("cycleway:right:oneway", "-1")
                    || way.hasTag("vehicle:forward", restrictedValues)
                    || way.hasTag("bicycle:forward", restrictedValues);
            accessEnc.setBool(isBackward, edgeId, edgeIntAccess, true);

        } else {
            accessEnc.setBool(true, edgeId, edgeIntAccess, true);
            accessEnc.setBool(false, edgeId, edgeIntAccess, true);
        }
    }
}
