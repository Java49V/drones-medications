package telran.drones.entities;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;
import telran.drones.dto.LogDto;
@Entity
@RequiredArgsConstructor
@NoArgsConstructor
@Table(name="logs")
@Getter
public class EventLog {
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
long id;
@ManyToOne
@JoinColumn(name="drone_number", nullable = false, updatable = false)
@NonNull Drone drone;
@ManyToOne
@JoinColumn(name="medication_code", nullable = false, updatable = false)
@NonNull Medication medication;
@Temporal(TemporalType.TIMESTAMP)
@Column(nullable = false, updatable = false)
@NonNull LocalDateTime timestamp;
public LogDto build() {
	return new LogDto(timestamp, drone.number, drone.state, drone.batteryCapacity, medication.code);
}


}
