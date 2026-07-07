package com.singleskickball.manager.config;

import com.singleskickball.manager.model.GameWeek;
import com.singleskickball.manager.model.GameWeekStatus;
import com.singleskickball.manager.model.Gender;
import com.singleskickball.manager.model.Player;
import com.singleskickball.manager.repository.GameWeekRepository;
import com.singleskickball.manager.repository.PlayerRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Seeds the initial league data.
 *
 * For this early version of the app, the registered player list and initial
 * seven-week game schedule are loaded automatically when the database is empty.
 *
 * Important production rule:
 * This initializer only inserts data when the relevant tables are empty. It does
 * not delete existing data, because player profile updates must survive restarts.
 */
@Configuration
public class DataInitializer {

    private static final String DEFAULT_PASSWORD = "password";

    @Bean
    CommandLineRunner seedData(PlayerRepository playerRepository,
                               GameWeekRepository gameWeekRepository,
                               PasswordEncoder passwordEncoder) {
        return args -> {
            seedPlayers(playerRepository, passwordEncoder);
            seedGameWeeks(gameWeekRepository);
        };
    }

    private void seedPlayers(PlayerRepository playerRepository,
                             PasswordEncoder passwordEncoder) {
        if (playerRepository.count() > 0) {
            return;
        }

        createPlayer(playerRepository, passwordEncoder, "Marisa Hombosky", "Marisa", Gender.FEMALE, "14123340788", "mhombosky432@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Stephen Gunzelman", "Stephen", Gender.MALE, "19372070428", "gunzelmans@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Natalie Wallace", null, Gender.FEMALE, "16143979995", "nwallace256@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Olivia Assaf", "Olivia", Gender.FEMALE, "16145786385", "olivialassaf@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Josh Duckson", "Josh", Gender.MALE, "19377018106", "jduckson30@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Arin Gunnarson", "Arin", Gender.FEMALE, "16147018088", "aringunnarson@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Manuel Tzagournis", "A.J.", Gender.MALE, "16149891425", "tzagournis.19@buckeyemail.osu.edu", false, false);
        createPlayer(playerRepository, passwordEncoder, "Greg Topps", "Topps", Gender.MALE, "16149294802", "gptopps@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Mary Clare McPherson", "MC", Gender.FEMALE, "16149409323", "mcmcph@yahoo.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Daniel Alexander", "Dan", Gender.MALE, "19293756141", "dg23alexander@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Brandon Donchez", "Poncho", Gender.MALE, "19374097575", "brandonkdonchez@aol.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Will King", "Will", Gender.MALE, "16145570017", "wcking2000@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Bre Cloutier", "Bre", Gender.FEMALE, "16116381294", "bcloutierwc@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Colton Coreno", "Colt", Gender.MALE, "17406018201", "cjcoreno12@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Corey White", "Corey", Gender.MALE, "15672453775", "cmwhite471@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Ashlee Davis", "AJ", Gender.FEMALE, "15095918949", "ashleeeedavis02@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Harshit Verma", "Harsh", Gender.MALE, "13046385074", "harsh.0201@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "El-amin Asadi", null, Gender.MALE, "16142095231", "elamin.asadi@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Elana Bobry", null, Gender.FEMALE, "15857036221", "elana.bobry@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Erica Wander", "Wander", Gender.FEMALE, "13308587953", "wanderfulcreative@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Paula Jones", "Paula J", Gender.FEMALE, "16145620047", "paulajones465465@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Benjamin Montello", "Ben", Gender.MALE, "16144488643", "bmontello@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Morgan Schneider", "Morgan", Gender.FEMALE, "19374706707", "morganschneider@outlook.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Sierra Clark", "Sierra", Gender.FEMALE, "17402582722", "sierraclarkdesign@gmail.com", false, false);
        createPlayer(playerRepository, passwordEncoder, "Garrick Schindler", null, Gender.MALE, "16144320858", "gmschindler@hotmail.com", false, false);

        createPlayer(playerRepository, passwordEncoder, "Dan Rubin", "Diesel", Gender.MALE, "16147452840", "superagentdan@gmail.com", true, true);
        createPlayer(playerRepository, passwordEncoder, "Kyle Crew", null, Gender.MALE, "19379265096", "kylecrew0@gmail.com", true, false);
    }

    private void seedGameWeeks(GameWeekRepository gameWeekRepository) {
        if (gameWeekRepository.count() > 0) {
            return;
        }

        LocalDate firstGameDate = LocalDate.of(2026, 7, 7);
        LocalTime gameTime = LocalTime.of(19, 0);

        for (int i = 0; i < 7; i++) {
            GameWeek week = new GameWeek();
            week.setGameDate(firstGameDate.plusWeeks(i));
            week.setGameTime(gameTime);
            week.setStatus(GameWeekStatus.OPEN_FOR_AVAILABILITY);
            gameWeekRepository.save(week);
        }
    }

    private void createPlayer(PlayerRepository repository,
                              PasswordEncoder passwordEncoder,
                              String name,
                              String nickname,
                              Gender gender,
                              String phone,
                              String email,
                              boolean manager,
                              boolean masterManager) {
        Player player = new Player();
        player.setName(name);
        player.setNickname(nickname);
        player.setGender(gender);
        player.setPhone(phone);
        player.setEmail(email);
        player.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        player.setManager(manager || masterManager);
        player.setMasterManager(masterManager);
        player.setActive(true);
        repository.save(player);
    }
}