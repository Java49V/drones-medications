package telran.drones.service;

import java.time.LocalDateTime;
import java.util.List;

import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import telran.drones.api.PropertiesNames;
import telran.drones.configuration.DronesConfiguration;
import telran.drones.dto.*;
import telran.drones.entities.*;
import telran.drones.exceptions.DroneAlreadyExistException;
import telran.drones.exceptions.DroneNotFoundException;
import telran.drones.exceptions.IllegalDroneStateException;
import telran.drones.exceptions.IllegalMedicationWeightException;
import telran.drones.exceptions.LowBatteryCapacityException;
import telran.drones.exceptions.MedicationNotFoundException;
import telran.drones.repo.*;
//import telran.drones.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DronesServiceImpl implements DronesService {
	final DroneRepo droneRepo;
	final MedicationRepo medicationRepo;
	final LogRepo logRepo;
	final ModelMapper modelMapper;
	final DronesConfiguration dronesConfiguration;
	@Value("${" + PropertiesNames.CAPACITY_THRESHOLD + ":25}")
	byte capacityThreshold;
	@Value("${" + PropertiesNames.PERIODIC_UNIT_MICROS + ":100}")
	long millisPerTimeUnit;

	@Override
	@Transactional
	public DroneDto registerDrone(DroneDto droneDto) {
		log.debug("service got drone DTO: {}", droneDto);
		if (droneRepo.existsById(droneDto.getNumber())) {
			throw new DroneAlreadyExistException();
		}
		Drone drone = modelMapper.map(droneDto, Drone.class);
		log.debug("mapped drone object is {}", drone);
		drone.setState(State.IDLE);
		droneRepo.save(drone);
		return droneDto;
	}

	@Override
	@Transactional(readOnly = false)
	public LogDto loadDrone(String droneNumber, String medicationCode) {
		log.debug("received: droneNumber={}, medicationCode={}", droneNumber, medicationCode);
		log.debug("capacity threshold is {}", capacityThreshold);
		Drone drone = droneRepo.findById(droneNumber).orElseThrow(() -> new DroneNotFoundException());
		log.debug("found drone: {}", drone);
		Medication medication = medicationRepo.findById(medicationCode)
				.orElseThrow(() -> new MedicationNotFoundException());
		log.debug("found medication: {}", medication);
		if (drone.getState() != State.IDLE) {
			throw new IllegalDroneStateException();
		}

		if (drone.getBatteryCapacity() < capacityThreshold) {
			throw new LowBatteryCapacityException();
		}
		if (drone.getWeightLimit() < medication.getWeight()) {
			throw new IllegalMedicationWeightException();
		}
		drone.setState(State.LOADING);
		EventLog eventLog = new EventLog(drone, medication, LocalDateTime.now());
		logRepo.save(eventLog);
		LogDto res = eventLog.build();
		log.debug("saved log: {}", res);

		return res;
	}

	@Override
	@Transactional(readOnly = true)
	public List<MedicationDto> checkMedicationItems(String droneNumber) {
		log.debug("received drone number {}", droneNumber);
		if (!droneRepo.existsById(droneNumber)) {
			throw new DroneNotFoundException();
		}
		List<EventLog> logs = logRepo.findByDroneNumberAndDroneState(droneNumber, State.LOADING);
		log.trace("found following logs: {}", logs.stream().map(EventLog::build).toList());
		return logs.stream().map(el -> modelMapper.map(el.getMedication(), MedicationDto.class)).toList();
	}

	@Override
	public List<DroneDto> checkAvailableDrones() {
		List<Drone> drones = droneRepo.findByStateAndBatteryCapacityGreaterThanEqual(State.IDLE,
				(byte) capacityThreshold);
		log.trace("found follwing drones: {}", drones);
		return drones.stream().map(d -> modelMapper.map(d, DroneDto.class)).toList();
	}

	@Override
	public int checkBatteryLevel(String droneNumber) {
		log.debug("received drone number: {}", droneNumber);

		Drone drone = droneRepo.findById(droneNumber).orElseThrow(() -> new DroneNotFoundException());
		return drone.getBatteryCapacity();
	}

	@Override
	public List<LogDto> checkLogs(String droneNumber) {
		if (!droneRepo.existsById(droneNumber)) {
			throw new DroneNotFoundException();
		}
		List<EventLog> logs = logRepo.findByDroneNumber(droneNumber);
		return logs.stream().map(EventLog::build).toList();
	}

	@Override
	public List<DroneMedicationsAmount> checkDronesMedicationItemsAmounts() {

		return logRepo.findDronesAmounts();
	}

	   @PostConstruct
	    void periodicTask() {
	        Thread thread = new Thread(() -> {
	            try {
	                while (true) {
	                    Thread.sleep(millisPerTimeUnit);

	                    List<Drone> drones = droneRepo.findAll();

	                    for (Drone drone : drones) {
	                        processDroneState(drone);
	                    }

	                    log.trace("Periodic task executed");
	                }

	            } catch (InterruptedException e) {
	                // Interruptions are not implemented
	            }
	        });
	        thread.setDaemon(true);
	        thread.start();
	    }

	private void processDroneState(Drone drone) {
		State currentState = drone.getState();
		State nextState = dronesConfiguration.getMovesMap().get(currentState);

		if (currentState == State.IDLE) {
			if (drone.getBatteryCapacity() < 100) {
				drone.setBatteryCapacity((byte) (drone.getBatteryCapacity() + 2));
				log.info("Charging drone {} battery capacity increased to {}", drone.getNumber(),
						drone.getBatteryCapacity());
				EventLog chargeLog = new EventLog(drone, null, LocalDateTime.now());
				logRepo.save(chargeLog);
			}
		} else {
			if (drone.getBatteryCapacity() > 0) {
				drone.setBatteryCapacity((byte) (drone.getBatteryCapacity() - 2));
				drone.setState(nextState);
				log.info("Drone {} moved from {} to {}", drone.getNumber(), currentState, nextState);
				EventLog moveLog = new EventLog(drone, null, LocalDateTime.now());
				logRepo.save(moveLog);
			}
		}
	    // Create EventLog for charging
	    if (currentState == State.IDLE) {
	        int updatedBatteryCapacity = Math.min(drone.getBatteryCapacity() + 2, 100);
	        drone.setBatteryCapacity((byte) updatedBatteryCapacity);
	        logRepo.save(new EventLog(drone, null, LocalDateTime.now())); // Medication is null for charging
	    }

	    // Create EventLog for moving to the next state
	    if (nextState != null) {
	        drone.setState(nextState);
	        int updatedBatteryCapacity = Math.max(drone.getBatteryCapacity() - 2, 0);
	        drone.setBatteryCapacity((byte) updatedBatteryCapacity);
	        logRepo.save(new EventLog(drone, null, LocalDateTime.now())); // Medication is null for moving
	    }

		droneRepo.save(drone);
	}

}
