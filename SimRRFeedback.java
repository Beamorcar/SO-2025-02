/**
 * Trabalho Avaliativo 1 – Sistemas Operacionais (Feevale 2025/2)
 * Simulador de Escalonamento: Round Robin com Feedback (MLFQ simples, 2 níveis)
 * Linguagem: Java (sem OOP avançada; classes simples e estáticas)
 *
 * <<< COMO APRESENTAR >>>
 * - Explicar as REGRAS antes de rodar:
 *   (1) Novos processos -> FILA ALTA
 *   (2) Preempção (fim do quantum) -> FILA BAIXA
 *   (3) Retorno de I/O: DISCO -> BAIXA | FITA/IMPRESSORA -> ALTA
 *   (4) Cada dispositivo de I/O atende 1 processo por vez (fila FIFO por dispositivo)
 *   (5) Round Robin por NÍVEL: quantum por fila ALTA/BAIXA
 *
 * <<< COMPILAÇÃO/EXECUÇÃO >>>
 *   javac SimRRFeedback.java
 *   java SimRRFeedback [N] [SEED] [QH] [QL] [MAX_BURSTS] [BASE_DISK] [BASE_TAPE] [BASE_PRINT]
 *
 * Exemplo:
 *   java SimRRFeedback 6 123 3 6 4 8 12 15
 */

import java.util.*;

public class SimRRFeedback {

    // ==========================
    // [PREMISSAS/VALORES PADRÃO]
    // ==========================
    static final int MAX_PROCS_LIMIT   = 20;   // limite absoluto interno
    static final int MAX_BURSTS_LIMIT  = 10;   // limite superior interno (rajadas)
    static final int QH_DEFAULT        = 3;    // quantum da fila ALTA (padrão)
    static final int QL_DEFAULT        = 6;    // quantum da fila BAIXA (padrão)
    static final int BASE_DISK_DEFAULT = 8;    // duração base de I/O (DISCO)
    static final int BASE_TAPE_DEFAULT = 12;   // duração base de I/O (FITA)
    static final int BASE_PRINT_DEFAULT= 15;   // duração base de I/O (IMPRESSORA)
    static final int MAX_BURSTS_DEFAULT= 4;    // máx. rajadas geradas (1..MAX_BURSTS_DEFAULT)
    static final int SEED_DEFAULT      = 42;   // semente aleatória padrão
    static final int N_DEFAULT         = 5;    // nº de processos padrão
    static final int SAFETY_TICKS      = 200_000; // trava de segurança

    // =================
    // [TIPOS AUXILIARES]
    // =================
    enum Device { NONE, DISK, TAPE, PRINTER }
    enum Status { NEW, READY, RUNNING, BLOCKED, TERMINATED }

    static String devName(Device d) {
        switch (d) {
            case DISK:    return "DISCO";
            case TAPE:    return "FITA";
            case PRINTER: return "IMPRESSORA";
            default:      return "NONE";
        }
    }
    static String prioName(int p) { return p == 0 ? "ALTA" : "BAIXA"; } // 0=ALTA, 1=BAIXA

    // Evento de I/O associado a uma rajada (entre bursts)
    static class IOEvent {
        Device dev = Device.NONE;
        int duration = 0;
    }

    // =========================
    // [PCB – BLOCO DE CONTROLE]
    // =========================
    static class PCB {
        // --- Identificação / prioridade / estado ---
        int pid;
        int ppid;         // não usado, mas mantido no PCB
        int priority;     // 0=ALTA, 1=BAIXA
        Status status;

        // --- Rajadas de CPU e I/O entre rajadas ---
        int numBursts;              // quantidade de rajadas
        int[] cpuBursts;            // duração de cada rajada
        IOEvent[] ios;              // I/O após cada rajada (exceto a última)
        int currentBurst;           // índice da rajada atual
        int remainingInBurst;       // restante da rajada atual
        int quantumRemaining;       // quantum restante (depende da fila ALTA/BAIXA)

        // --- Métricas (para relatório) ---
        int arrivalTime;            // todos chegam no tick 0
        int firstResponseTime;      // tempo até primeira vez na CPU
        int endTime;                // término (tick)
        int preemptions;            // contagem de preempções
        int ioCount;                // quantos I/Os realizou
        int waitTimeReady;          // total de espera em filas READY
        int lastReadyEnqueueTick;   // para acumular espera quando despacha para CPU
    }

    // ======================
    // [DISPOSITIVO DE I/O]
    // ======================
    static class DeviceState {
        Device dev;
        int busyPid = -1;      // -1 se o dispositivo está ocioso
        int remaining = 0;     // ticks restantes do I/O atual
        Deque<Integer> queue = new ArrayDeque<>(); // fila FIFO de PIDs
        int baseDuration;      // duração base do I/O deste dispositivo

        DeviceState(Device dev, int base) {
            this.dev = dev;
            this.baseDuration = base;
        }
    }

    // ============
    // [ALEATORIEDADE]
    // ============
    static int randRange(Random rng, int a, int b) { // uniforme em [a..b]
        return a + rng.nextInt(b - a + 1);
    }

    // ====================================================
    // [GERAÇÃO DE PROCESSOS: rajadas + I/O entre rajadas]
    // ====================================================
    static void generateProcesses(
            PCB[] procs, int N, int maxBursts,
            int baseDisk, int baseTape, int basePrint,
            Random rng
    ) {
        for (int i = 0; i < N; i++) {
            PCB p = new PCB();
            p.pid = i + 1;
            p.ppid = 0;
            p.priority = 0;           // começa na FILA ALTA
            p.status = Status.READY;

            p.numBursts = randRange(rng, 1, maxBursts);
            if (p.numBursts > MAX_BURSTS_LIMIT) p.numBursts = MAX_BURSTS_LIMIT;

            p.cpuBursts = new int[p.numBursts];
            p.ios = new IOEvent[p.numBursts];
            for (int b = 0; b < p.numBursts; b++) {
                // Duração da rajada de CPU: 3..10 ticks
                p.cpuBursts[b] = randRange(rng, 3, 10);

                IOEvent ev = new IOEvent();
                if (b < p.numBursts - 1) {
                    // Sorteia o tipo de I/O entre as rajadas
                    int r = randRange(rng, 1, 3);
                    Device d = (r == 1 ? Device.DISK : (r == 2 ? Device.TAPE : Device.PRINTER));
                    int base = (d == Device.DISK ? baseDisk : (d == Device.TAPE ? baseTape : basePrint));

                    // Variação ±20% sobre a base do dispositivo
                    double factor = 0.8 + (rng.nextInt(41) / 100.0);
                    int dur = (int) Math.round(base * factor);
                    if (dur < 1) dur = 1;
                    ev.dev = d;
                    ev.duration = dur;
                } // última rajada não tem I/O
                p.ios[b] = ev;
            }

            // Inicializa ponteiros de execução
            p.currentBurst = 0;
            p.remainingInBurst = p.cpuBursts[0];
            p.quantumRemaining = 0;

            // Métricas
            p.arrivalTime = 0;
            p.firstResponseTime = -1;
            p.endTime = -1;
            p.preemptions = 0;
            p.ioCount = 0;
            p.waitTimeReady = 0;
            p.lastReadyEnqueueTick = 0; // entram nas filas no tick 0

            procs[i] = p;
        }
    }

    public static void main(String[] args) {
        // =========================
        // [LEITURA DE PARÂMETROS]
        // =========================
        int N          = (args.length >= 1) ? Integer.parseInt(args[0]) : N_DEFAULT;
        int SEED       = (args.length >= 2) ? Integer.parseInt(args[1]) : SEED_DEFAULT;
        int QH         = (args.length >= 3) ? Integer.parseInt(args[2]) : QH_DEFAULT;
        int QL         = (args.length >= 4) ? Integer.parseInt(args[3]) : QL_DEFAULT;
        int MAX_B      = (args.length >= 5) ? Integer.parseInt(args[4]) : MAX_BURSTS_DEFAULT;
        int BASE_DISK  = (args.length >= 6) ? Integer.parseInt(args[5]) : BASE_DISK_DEFAULT;
        int BASE_TAPE  = (args.length >= 7) ? Integer.parseInt(args[6]) : BASE_TAPE_DEFAULT;
        int BASE_PRINT = (args.length >= 8) ? Integer.parseInt(args[7]) : BASE_PRINT_DEFAULT;

        if (N < 1) N = 1;
        if (N > MAX_PROCS_LIMIT) N = MAX_PROCS_LIMIT;

        // ===============
        // [ESTADO GLOBAL]
        // ===============
        Random rng = new Random(SEED);
        PCB[] procs = new PCB[N];
        generateProcesses(procs, N, MAX_B, BASE_DISK, BASE_TAPE, BASE_PRINT, rng);

        // Filas de prontos (Round Robin por nível)
        Deque<Integer> readyHigh = new ArrayDeque<>();
        Deque<Integer> readyLow  = new ArrayDeque<>();

        // Todos os processos novos -> FILA ALTA (regra)
        for (int i = 0; i < N; i++) {
            readyHigh.addLast(procs[i].pid);
            procs[i].lastReadyEnqueueTick = 0;
        }

        // Dispositivos de I/O: cada um atende 1 processo por vez (fila FIFO)
        DeviceState dDisk  = new DeviceState(Device.DISK, BASE_DISK);
        DeviceState dTape  = new DeviceState(Device.TAPE, BASE_TAPE);
        DeviceState dPrint = new DeviceState(Device.PRINTER, BASE_PRINT);
        DeviceState[] devs = new DeviceState[] { dDisk, dTape, dPrint };

        int runningPid = -1;  // -1 = CPU ociosa neste tick
        int time = 0;         // relógio discreto (ticks)
        int finished = 0;     // quantos processos já finalizaram
        int cpuBusyTicks = 0; // para calcular utilização da CPU

        // Cabeçalho informativo
        System.out.println("=== SIMULACAO RR com FEEDBACK (MLFQ 2 niveis) ===");
        System.out.printf("N=%d SEED=%d QH=%d QL=%d MAX_B=%d BASES: DISK=%d, TAPE=%d, PRINT=%d%n%n",
                N, SEED, QH, QL, MAX_B, BASE_DISK, BASE_TAPE, BASE_PRINT);

        // ======================
        // [LAÇO DE SIMULAÇÃO]
        // ======================
        while (finished < N && time <= SAFETY_TICKS) {

            // ----------------------------------------------------
            // [DESPACHO / DISPATCH]
            // Se não há processo executando, escolhe da FILA ALTA;
            // se vazia, escolhe da FILA BAIXA. Define quantum por nível.
            // ----------------------------------------------------
            if (runningPid == -1) {
                Integer pid = readyHigh.pollFirst();
                if (pid == null) pid = readyLow.pollFirst();
                if (pid != null) {
                    runningPid = pid;
                    PCB p = procs[runningPid - 1];

                    p.status = Status.RUNNING;
                    p.quantumRemaining = (p.priority == 0 ? QH : QL);

                    // Tempo de resposta: primeira vez que roda
                    if (p.firstResponseTime < 0) {
                        p.firstResponseTime = time - p.arrivalTime;
                    }

                    // Ao sair da fila READY para a CPU, acumula a espera
                    p.waitTimeReady += (time - p.lastReadyEnqueueTick);

                    System.out.printf("[t=%04d] DISPATCH P%d (prio=%s, q=%d, burst_rem=%d)%n",
                            time, p.pid, prioName(p.priority), p.quantumRemaining, p.remainingInBurst);
                }
            }

            // ----------------------------------------------------
            // [EXECUÇÃO DA CPU – 1 TICK]
            // Se há processo na CPU, desconta 1 de burst e 1 de quantum.
            // Verifica (a) término da rajada -> I/O ou fim do processo
            //          (b) término do quantum -> PREEMPÇÃO para FILA BAIXA
            // ----------------------------------------------------
            if (runningPid != -1) {
                PCB p = procs[runningPid - 1];

                p.remainingInBurst--;
                p.quantumRemaining--;
                cpuBusyTicks++; // CPU ocupada neste tick

                // (a) TÉRMINO DA RAJADA DE CPU
                if (p.remainingInBurst == 0) {
                    System.out.printf("[t=%04d] P%d terminou RAJADA %d%n", time + 1, p.pid, p.currentBurst + 1);

                    // Há nova rajada? Se sim, BLOQUEIA em I/O conforme evento
                    if (p.currentBurst < p.numBursts - 1) {
                        IOEvent ev = p.ios[p.currentBurst];
                        p.ioCount++;
                        p.status = Status.BLOCKED;

                        // Encaminha para o DISPOSITIVO correspondente
                        DeviceState dev = (ev.dev == Device.DISK) ? dDisk
                                         : (ev.dev == Device.TAPE) ? dTape : dPrint;

                        if (dev.busyPid == -1) {
                            // Dispositivo ocioso: inicia I/O imediatamente
                            dev.busyPid = p.pid;
                            dev.remaining = ev.duration;
                            System.out.printf("[t=%04d] P%d INICIO I/O (%s) dur=%d%n",
                                    time + 1, p.pid, devName(ev.dev), ev.duration);
                        } else {
                            // Dispositivo ocupado: entra na FILA do dispositivo
                            dev.queue.addLast(p.pid);
                            System.out.printf("[t=%04d] P%d ENTRA FILA I/O (%s)%n",
                                    time + 1, p.pid, devName(ev.dev));
                        }

                        // Prepara a próxima rajada (quando voltar do I/O)
                        p.currentBurst++;
                        p.remainingInBurst = p.cpuBursts[p.currentBurst];

                        // Libera a CPU
                        runningPid = -1;
                    } else {
                        // Não há nova rajada -> TERMINO do processo
                        p.status = Status.TERMINATED;
                        p.endTime = time + 1;
                        runningPid = -1;
                        finished++;
                        System.out.printf("[t=%04d] P%d TERMINOU (turnaround=%d)%n",
                                time + 1, p.pid, (p.endTime - p.arrivalTime));
                    }
                }
                // (b) TÉRMINO DO QUANTUM (PREEMPÇÃO)
                else if (p.quantumRemaining == 0) {
                    p.preemptions++;
                    p.priority = 1; // vai para FILA BAIXA
                    p.status = Status.READY;
                    p.lastReadyEnqueueTick = time + 1;
                    readyLow.addLast(p.pid);

                    System.out.printf("[t=%04d] PREEMPCAO P%d -> fila BAIXA (burst_rem=%d)%n",
                            time + 1, p.pid, p.remainingInBurst);

                    // Libera a CPU
                    runningPid = -1;
                }

            } else {
                // CPU Ociosa neste tick (opcional log)
                // System.out.printf("[t=%04d] CPU OCIOSA%n", time);
            }

            // ----------------------------------------------------
            // [ATUALIZAÇÃO DOS DISPOSITIVOS DE I/O – 1 TICK]
            // Cada dispositivo atende 1 processo por vez. Ao terminar:
            // Regra de RETORNO: DISCO -> BAIXA | FITA/IMPRESSORA -> ALTA
            // ----------------------------------------------------
            for (DeviceState dv : devs) {
                if (dv.busyPid != -1) {
                    dv.remaining--;
                    if (dv.remaining == 0) {
                        int pidDone = dv.busyPid;
                        dv.busyPid = -1;

                        PCB p = procs[pidDone - 1];
                        p.status = Status.READY;

                        boolean toHigh = (dv.dev == Device.TAPE || dv.dev == Device.PRINTER);
                        if (toHigh) {
                            p.priority = 0; // FILA ALTA
                            p.lastReadyEnqueueTick = time + 1;
                            readyHigh.addLast(p.pid);
                        } else {
                            p.priority = 1; // FILA BAIXA
                            p.lastReadyEnqueueTick = time + 1;
                            readyLow.addLast(p.pid);
                        }

                        System.out.printf("[t=%04d] P%d FIM I/O (%s) -> fila %s%n",
                                time + 1, p.pid, devName(dv.dev), prioName(p.priority));

                        // Se houver espera no dispositivo, inicia próximo
                        Integer nextPid = dv.queue.pollFirst();
                        if (nextPid != null) {
                            PCB np = procs[nextPid - 1];
                            // O evento de I/O que ele está cumprindo é o da rajada anterior
                            IOEvent ev = np.ios[np.currentBurst - 1];
                            dv.busyPid = nextPid;
                            dv.remaining = ev.duration;

                            System.out.printf("[t=%04d] P%d INICIO I/O (%s) dur=%d%n",
                                    time + 1, np.pid, devName(dv.dev), ev.duration);
                        }
                    }
                } else {
                    // Dispositivo ocioso e fila não vazia: inicia próximo
                    Integer nextPid = dv.queue.pollFirst();
                    if (nextPid != null) {
                        PCB np = procs[nextPid - 1];
                        IOEvent ev = np.ios[np.currentBurst - 1];
                        dv.busyPid = nextPid;
                        dv.remaining = ev.duration;

                        System.out.printf("[t=%04d] P%d INICIO I/O (%s) dur=%d%n",
                                time + 1, np.pid, devName(dv.dev), ev.duration);
                    }
                }
            }

            // Avança 1 tick no relógio global
            time++;
        }

        if (time > SAFETY_TICKS) {
            System.err.println("Simulacao muito longa (safety). Encerrando.");
        }

        // ===========================
        // [SUMÁRIO / MÉTRICAS FINAIS]
        // ===========================
        System.out.println("\n=== SUMARIO ===");
        double avgTurn = 0.0, avgWait = 0.0, avgResp = 0.0;

        for (PCB p : procs) {
            int turnaround = p.endTime - p.arrivalTime;
            avgTurn += turnaround;
            avgWait += p.waitTimeReady;
            avgResp += p.firstResponseTime;

            System.out.printf(
                    "P%-2d: bursts=%d, io=%d, preemp=%d, resp=%d, wait=%d, turn=%d%n",
                    p.pid, p.numBursts, p.ioCount, p.preemptions,
                    p.firstResponseTime, p.waitTimeReady, turnaround
            );
        }

        avgTurn /= procs.length;
        avgWait /= procs.length;
        avgResp /= procs.length;
        double cpuUtil = (time > 0) ? (100.0 * cpuBusyTicks / time) : 0.0;

        System.out.printf(
                "%nTempo total: %d ticks | CPU busy: %d (%.2f%%)%n",
                time, cpuBusyTicks, cpuUtil
        );
        System.out.printf(
                "Medias -> Turnaround: %.2f | Espera(READY): %.2f | Resposta: %.2f%n",
                avgTurn, avgWait, avgResp
        );
    }
}
